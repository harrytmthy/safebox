/*
 * Copyright 2025 Harry Timothy Tumalewa
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.harrytmthy.safebox.engine

import android.content.Context
import android.os.Build
import androidx.annotation.VisibleForTesting
import com.harrytmthy.safebox.SafeBox.Action
import com.harrytmthy.safebox.SafeBox.Action.Put
import com.harrytmthy.safebox.SafeBox.Action.Remove
import com.harrytmthy.safebox.SafeBox.FailureListener
import com.harrytmthy.safebox.cryptography.CipherProvider
import com.harrytmthy.safebox.decoder.ByteDecoder
import com.harrytmthy.safebox.diagnostics.FailureKind
import com.harrytmthy.safebox.diagnostics.FailureNotifier
import com.harrytmthy.safebox.diagnostics.FailureOperation
import com.harrytmthy.safebox.extensions.safeBoxScope
import com.harrytmthy.safebox.extensions.toBytes
import com.harrytmthy.safebox.storage.Bytes
import com.harrytmthy.safebox.storage.SafeBoxBlobStore
import com.harrytmthy.safebox.storage.SafeBoxRecoveryBlobStore
import com.harrytmthy.safebox.strategy.ValueFallbackStrategy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.AEADBadTagException

internal class SafeBoxEngine private constructor(
    private val blobStore: SafeBoxBlobStore,
    private val recoveryBlobStore: SafeBoxRecoveryBlobStore,
    keyCipherProvider: CipherProvider,
    private val valueCipherProvider: CipherProvider,
    private val ioDispatcher: CoroutineDispatcher,
    private val notifyNullOnClear: Boolean,
    private val failureNotifier: FailureNotifier,
) {

    private val entries = EntryIndex(keyCipherProvider)

    private val unreadableRecords = ConcurrentHashMap<Bytes, AEADBadTagException>()

    // Null means the primary mutation succeeded and only journal retirement remains.
    private val recoveryEntries = HashMap<Bytes, ByteArray?>()

    private val fileNameBytes = blobStore.getFileName().toBytes()

    private val pendingActions = LinkedHashMap<String, EncryptedAction>()

    private var pendingClear = false

    private val byteDecoder = ByteDecoder()

    private val updateLock = Any()

    private val pendingUpdateLock = Any()

    private val initialReadCompleted = CompletableDeferred<Unit>()

    private var retryInitialStorageRead = false

    private val writeBarrier = AtomicReference(CompletableDeferred<Unit>().apply { complete(Unit) })

    private val writeMutex = Mutex()

    private val scanScheduled = AtomicBoolean(false)

    private val recoveryScheduled = AtomicBoolean(false)

    private var callback: Callback? = null

    private var writeDebounceJob: Job? = null

    init {
        launchWithStartingState {
            val loadedEntries = try {
                loadStartingEntries()
            } catch (e: Exception) {
                retryInitialStorageRead = true
                throw e
            }
            for ((encryptedKey, encryptedValue) in loadedEntries) {
                val key = try {
                    entries.decodeKey(encryptedKey)
                } catch (e: AEADBadTagException) {
                    failureNotifier.notify(
                        error = e,
                        kind = FailureKind.DECRYPT_KEY,
                        operation = FailureOperation.INITIAL_LOAD,
                    )
                    unreadableRecords[encryptedKey] = e
                    continue
                } catch (e: Exception) {
                    failureNotifier.notify(
                        error = e,
                        kind = FailureKind.DECRYPT_KEY,
                        operation = FailureOperation.INITIAL_LOAD,
                    )
                    return@launchWithStartingState
                }
                withFailureKind(FailureKind.INDEX_KEY) {
                    entries.load(key, encryptedKey, encryptedValue)
                }
            }
        }
    }

    fun setCastFailureStrategy(fallbackStrategy: ValueFallbackStrategy) {
        byteDecoder.setCastFailureStrategy(fallbackStrategy)
    }

    fun setCallback(callback: Callback?) {
        this.callback = callback
    }

    fun contains(key: String): Boolean {
        awaitInitialReadBlocking()
        return entries[key] != null
    }

    fun getEntries(): Map<String, Any?> {
        awaitInitialReadBlocking()
        val decryptedEntries = HashMap<String, Any?>(entries.size, 1f)
        for (entry in entries.values()) {
            val key = decodeKey(entry) ?: continue
            val value = valueCipherProvider.tryDecrypt(
                entry = entry,
                operation = FailureOperation.READ,
                action = null,
            ) ?: continue
            decryptedEntries[key] = byteDecoder.decodeAny(value)
        }
        return decryptedEntries
    }

    inline fun <reified T> getValue(key: String, defValue: T): T {
        awaitInitialReadBlocking()
        return entries[key]
            ?.let { valueCipherProvider.tryDecrypt(it, FailureOperation.READ, action = null) }
            ?.let { byteDecoder.decodeAny(it) as T }
            ?: defValue
    }

    fun commitBatch(actions: LinkedHashMap<String, Action>, cleared: Boolean): Boolean {
        if (actions.isEmpty() && !cleared) {
            return true
        }
        awaitInitialReadBlocking()
        if (writeDebounceJob?.isActive == true) {
            writeDebounceJob?.cancel()
            writeDebounceJob = null
            applyPendingActions()
        }
        val snapshot = LinkedHashMap(actions)
        actions.clear() // Prevents stale mutations on reused editor instance
        val encryptedActions = updateEntries(snapshot, cleared)
        return launchWriteBlocking(encryptedActions, cleared) {
            applyChanges(encryptedActions, cleared)
        }
    }

    fun applyBatch(actions: LinkedHashMap<String, Action>, cleared: Boolean) {
        if (actions.isEmpty() && !cleared) {
            return
        }
        awaitInitialReadBlocking()
        if (writeDebounceJob?.isActive == true) {
            writeDebounceJob?.cancel()
            writeDebounceJob = null
        }
        val snapshot = LinkedHashMap(actions)
        actions.clear() // Prevents stale mutations on reused editor instance
        updateEntries(snapshot, cleared, enqueue = true)
        writeDebounceJob = safeBoxScope.launch(ioDispatcher) {
            delay(WRITE_DEBOUNCE_TIMEOUT_MS)
            applyPendingActions()
        }
    }

    private fun applyPendingActions() {
        val (pendingActionsSnapshot, shouldClear) = synchronized(pendingUpdateLock) {
            val snapshot = LinkedHashMap(pendingActions)
            pendingActions.clear()
            val cleared = pendingClear.also { pendingClear = false }
            snapshot to cleared
        }
        launchWriteAsync(
            actions = pendingActionsSnapshot,
            cleared = shouldClear,
        ) {
            applyChanges(pendingActionsSnapshot, shouldClear)
        }
    }

    private fun updateEntries(
        actions: LinkedHashMap<String, Action>,
        cleared: Boolean,
        enqueue: Boolean = false,
    ): LinkedHashMap<String, EncryptedAction> {
        val encryptedActions = LinkedHashMap<String, EncryptedAction>(actions.size)
        val modifiedKeys = synchronized(updateLock) {
            if (cleared) {
                entries.clear()
                if (enqueue) {
                    synchronized(pendingUpdateLock) {
                        pendingActions.clear()
                        pendingClear = true
                    }
                }
                if (notifyNullOnClear) {
                    callback?.onEntryChanged(null)
                }
            }
            val modifiedKeys = callback?.let { ArrayList<String>(actions.size) }
            for (entry in actions) {
                val (key, action) = entry
                val lookup = entries.lookup(key)
                val pendingRecordId = synchronized(pendingUpdateLock) {
                    pendingActions[key]?.encryptedKey
                }
                val encryptedKey = try {
                    entries.resolveEncryptedKey(key, lookup, pendingRecordId)
                } catch (e: Exception) {
                    failureNotifier.notify(
                        error = e,
                        kind = FailureKind.ENCRYPT_KEY,
                        operation = FailureOperation.WRITE,
                        action = entry,
                    )
                    throw e
                }
                when (action) {
                    is Put -> {
                        val oldEncryptedValue = lookup.entry?.encryptedValue
                        val oldValue = lookup.entry?.let {
                            valueCipherProvider.tryDecrypt(
                                entry = it,
                                operation = FailureOperation.WRITE,
                                action = entry,
                            )
                        }
                        val newValue = action.encodedValue.value
                        val encryptedValue = if (newValue.contentEquals(oldValue)) {
                            oldEncryptedValue!!
                        } else {
                            encryptValue(newValue, entry).also {
                                entries.put(lookup, encryptedKey, it)
                                modifiedKeys?.add(key)
                            }
                        }
                        encryptedActions[key] = EncryptedAction(
                            key,
                            action,
                            encryptedKey,
                            encryptedValue,
                        )
                    }
                    is Remove -> {
                        if (entries.remove(lookup) != null) {
                            modifiedKeys?.add(key)
                        }
                        encryptedActions[key] = EncryptedAction(key, action, encryptedKey, null)
                    }
                }
            }
            if (enqueue) {
                synchronized(pendingUpdateLock) {
                    pendingActions += encryptedActions
                }
            }
            modifiedKeys
        }
        for (index in (modifiedKeys?.size ?: return encryptedActions) - 1 downTo 0) {
            callback?.onEntryChanged(modifiedKeys[index])
        }
        return encryptedActions
    }

    private suspend fun applyChanges(
        entries: LinkedHashMap<String, EncryptedAction>,
        cleared: Boolean,
    ) {
        if (cleared) {
            val supersedes = recoveryEntries.isNotEmpty()
            withFailureKind(FailureKind.PRIMARY_CLEAR) { blobStore.deleteAll(supersedes) }
            if (supersedes) {
                discardRecoveryEntries()
            }
        }
        for (entry in entries.values) {
            val encryptedKey = entry.encryptedKey
            when (entry.value) {
                is Put -> {
                    val encryptedValue = entry.encryptedValue!!
                    val supersedes = hasRecoveryEntry(encryptedKey)
                    try {
                        withFailureKind(FailureKind.PRIMARY_WRITE) {
                            blobStore.write(encryptedKey, encryptedValue, supersedes)
                        }
                    } catch (e: Exception) {
                        failureNotifier.notify(
                            error = e.originalFailure(),
                            kind = e.failureKind(),
                            operation = FailureOperation.WRITE,
                            action = entry,
                        )
                        withFailureKind(FailureKind.RECOVERY_WRITE) {
                            recoveryBlobStore.write(
                                fileName = fileNameBytes,
                                encryptedKey = encryptedKey,
                                encryptedValue = encryptedValue,
                                forceNow = false,
                            )
                        }
                        // Keep mapped recovery data tracked even if its flush fails.
                        recoveryEntries[encryptedKey] = encryptedValue
                        withFailureKind(FailureKind.RECOVERY_FLUSH) {
                            recoveryBlobStore.flushPendingChanges()
                        }
                        continue
                    }
                    if (supersedes) {
                        discardRecoveryEntry(encryptedKey)
                    }
                }
                is Remove -> {
                    val supersedes = hasRecoveryEntry(encryptedKey)
                    if (blobStore.contains(encryptedKey)) {
                        withFailureKind(FailureKind.PRIMARY_REMOVE) {
                            blobStore.delete(encryptedKey, supersedes)
                        }
                    }
                    if (supersedes) {
                        discardRecoveryEntry(encryptedKey)
                    }
                }
            }
        }
    }

    private fun hasRecoveryEntry(encryptedKey: Bytes): Boolean =
        recoveryEntries.isNotEmpty() && recoveryEntries.containsKey(encryptedKey)

    private suspend fun discardRecoveryEntry(encryptedKey: Bytes) {
        recoveryEntries[encryptedKey] = null
        withFailureKind(FailureKind.RECOVERY_REMOVE) {
            recoveryBlobStore.delete(fileNameBytes, encryptedKey)
        }
        recoveryEntries.remove(encryptedKey)
    }

    private suspend fun discardRecoveryEntries() {
        for (entry in recoveryEntries.entries) {
            entry.setValue(null)
        }
        withFailureKind(FailureKind.RECOVERY_CLEAR) { recoveryBlobStore.delete(fileNameBytes) }
        recoveryEntries.clear()
    }

    private fun scheduleRecoveryEntriesWrite(recoveryBackoffMs: Long) {
        safeBoxScope.launch(ioDispatcher) {
            delay(recoveryBackoffMs)
        }.invokeOnCompletion {
            val nextBackoffMs = (recoveryBackoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            launchWriteAsync(nextBackoffMs, operation = FailureOperation.REPLAY) {
                replayRecoveryEntriesWithContext()
            }
        }
    }

    @VisibleForTesting
    internal suspend fun replayRecoveryEntries() {
        try {
            replayRecoveryEntriesWithContext()
        } catch (e: OperationFailure) {
            throw e.error
        }
    }

    private suspend fun replayRecoveryEntriesWithContext() {
        withFailureKind(FailureKind.RECOVERY_FLUSH) { recoveryBlobStore.flushPendingChanges() }
        val snapshot = ArrayList(recoveryEntries.entries)
        val replayedKeys = ArrayList<Bytes>()
        for ((encryptedKey, encryptedValue) in snapshot) {
            if (encryptedValue == null) {
                discardRecoveryEntry(encryptedKey)
                continue
            }
            try {
                withFailureKind(FailureKind.PRIMARY_WRITE) {
                    blobStore.write(encryptedKey, encryptedValue, false)
                }
                replayedKeys += encryptedKey
            } catch (e: Exception) {
                failureNotifier.notify(
                    error = e.originalFailure(),
                    kind = e.failureKind(),
                    operation = FailureOperation.REPLAY,
                )
                continue
            }
        }
        if (replayedKeys.isEmpty()) {
            return
        }
        withFailureKind(FailureKind.PRIMARY_FLUSH) { blobStore.flushDirtyPages() }
        for (encryptedKey in replayedKeys) {
            recoveryEntries[encryptedKey] = null
        }
        withFailureKind(FailureKind.RECOVERY_REMOVE) {
            recoveryBlobStore.delete(fileNameBytes, *replayedKeys.toTypedArray())
        }
        for (encryptedKey in replayedKeys) {
            recoveryEntries.remove(encryptedKey)
        }
    }

    private suspend fun loadStartingEntries(): Map<Bytes, ByteArray> {
        val loadedEntries = LinkedHashMap(
            withFailureKind(FailureKind.PRIMARY_LOAD) {
                blobStore.loadPersistedEntries()
            },
        )
        val recoveredEntries =
            withFailureKind(FailureKind.RECOVERY_LOAD) {
                recoveryBlobStore.loadPersistedEntries(fileNameBytes)
            }
        loadedEntries += recoveredEntries
        recoveryEntries += recoveredEntries
        return loadedEntries
    }

    private suspend fun retryStartingStorageRead() {
        if (!retryInitialStorageRead) {
            return
        }
        // Rebuild storage locations without replacing mutations already applied in memory.
        loadStartingEntries()
        retryInitialStorageRead = false
    }

    private inline fun launchWithStartingState(crossinline block: suspend () -> Unit) {
        safeBoxScope.launch(ioDispatcher) {
            try {
                block()
            } catch (e: Exception) {
                failureNotifier.notify(
                    error = e.originalFailure(),
                    kind = e.failureKind(),
                    operation = FailureOperation.INITIAL_LOAD,
                )
            } finally {
                initialReadCompleted.complete(Unit)
            }
        }.invokeOnCompletion {
            if (unreadableRecords.isNotEmpty()) {
                scanAndRemoveDeadEntries()
            }
            if (recoveryEntries.isNotEmpty()) {
                recoveryScheduled.set(true)
                scheduleRecoveryEntriesWrite(DEFAULT_BACKOFF_MS)
            }
        }
    }

    private inline fun launchWriteBlocking(
        actions: Map<String, EncryptedAction>,
        cleared: Boolean,
        crossinline block: suspend () -> Unit,
    ): Boolean {
        val currentWriteBarrier = CompletableDeferred<Unit>()
        val previousWriteBarrier = writeBarrier.getAndSet(currentWriteBarrier)
        return runBlocking {
            var committed = try {
                initialReadCompleted.await()
                previousWriteBarrier.await()
                writeMutex.withLock {
                    retryStartingStorageRead()
                    block()
                }
                true
            } catch (e: Exception) {
                failureNotifier.notifyBatch(
                    error = e.originalFailure(),
                    kind = e.failureKind(),
                    operation = FailureOperation.WRITE,
                    actions = actions,
                    cleared = cleared,
                )
                false
            }
            try {
                withFailureKind(FailureKind.PRIMARY_FLUSH) { blobStore.flushDirtyPages() }
            } catch (e: Exception) {
                failureNotifier.notifyBatch(
                    error = e.originalFailure(),
                    kind = e.failureKind(),
                    operation = FailureOperation.WRITE,
                    actions = actions,
                    cleared = cleared,
                )
                committed = false
            } finally {
                currentWriteBarrier.complete(Unit)
                if (recoveryEntries.isNotEmpty() && recoveryScheduled.compareAndSet(false, true)) {
                    scheduleRecoveryEntriesWrite(DEFAULT_BACKOFF_MS)
                }
            }
            committed
        }
    }

    private inline fun launchWriteAsync(
        recoveryBackoffMs: Long = DEFAULT_BACKOFF_MS,
        operation: FailureOperation = FailureOperation.WRITE,
        crossinline onFlushed: () -> Unit = {},
        actions: Map<String, EncryptedAction>? = null,
        cleared: Boolean = false,
        crossinline block: suspend () -> Unit,
    ) {
        val currentWriteBarrier = CompletableDeferred<Unit>()
        val previousWriteBarrier = writeBarrier.getAndSet(currentWriteBarrier)
        safeBoxScope.launch(ioDispatcher) {
            try {
                initialReadCompleted.await()
                previousWriteBarrier.await()
                writeMutex.withLock {
                    retryStartingStorageRead()
                    block()
                }
            } catch (e: Exception) {
                failureNotifier.notifyBatch(
                    error = e.originalFailure(),
                    kind = e.failureKind(),
                    operation = operation,
                    actions = actions,
                    cleared = cleared,
                )
            } finally {
                try {
                    withFailureKind(FailureKind.PRIMARY_FLUSH) { blobStore.flushDirtyPages() }
                    onFlushed()
                } catch (e: Exception) {
                    failureNotifier.notifyBatch(
                        error = e.originalFailure(),
                        kind = e.failureKind(),
                        operation = operation,
                        actions = actions,
                        cleared = cleared,
                    )
                } finally {
                    currentWriteBarrier.complete(Unit)
                }
            }
        }.invokeOnCompletion {
            if (recoveryBackoffMs == DEFAULT_BACKOFF_MS && recoveryScheduled.get()) {
                return@invokeOnCompletion
            }
            if (recoveryEntries.isNotEmpty()) {
                recoveryScheduled.set(true)
                scheduleRecoveryEntriesWrite(recoveryBackoffMs)
            } else {
                recoveryScheduled.set(false)
            }
        }
    }

    internal fun awaitInitialReadBlocking() {
        if (!initialReadCompleted.isCompleted) {
            runBlocking { initialReadCompleted.await() }
        }
    }

    internal fun closeBlobStoreChannel() {
        if (writeDebounceJob?.isActive == true) {
            writeDebounceJob?.cancel()
            writeDebounceJob = null
            applyPendingActions()
        }
        runBlocking {
            initialReadCompleted.await()
            writeBarrier.get().await()
            blobStore.closeWhenIdle()
        }
    }

    private fun scanAndRemoveDeadEntries() {
        if (!scanScheduled.compareAndSet(false, true)) {
            return
        }
        var removalCause: AEADBadTagException? = null
        var removedCount = 0
        launchWriteAsync(
            operation = FailureOperation.CLEANUP,
            onFlushed = {
                removalCause?.let { failureNotifier.notifyRemoval(it, removedCount) }
            },
        ) {
            try {
                val pendingCount = unreadableRecords.size
                val deadKeys = LinkedHashMap<Bytes, AEADBadTagException>(pendingCount, 1f)
                val detectedFailures = HashMap<Bytes, AEADBadTagException>(pendingCount, 1f)
                synchronized(updateLock) {
                    val currentRecordIds = HashSet<Bytes>(entries.size, 1f)
                    entries.values().forEach { currentRecordIds.add(it.encryptedKey) }
                    for ((encryptedKey, cause) in unreadableRecords) {
                        detectedFailures[encryptedKey] = cause
                        if (encryptedKey !in currentRecordIds) {
                            deadKeys[encryptedKey] = cause
                        }
                        unreadableRecords.remove(encryptedKey, cause)
                    }
                }
                for (entry in entries.values()) {
                    try {
                        valueCipherProvider.decrypt(entry.encryptedValue)
                    } catch (e: AEADBadTagException) {
                        synchronized(updateLock) {
                            if (entries.remove(entry)) {
                                deadKeys[entry.encryptedKey] =
                                    detectedFailures[entry.encryptedKey] ?: e
                            }
                        }
                    } catch (e: Exception) {
                        throw OperationFailure(e, FailureKind.DECRYPT_VALUE)
                    }
                }
                if (deadKeys.isEmpty()) {
                    return@launchWriteAsync
                }
                val recoveryRecordIds = recoveryBlobStore.getEncryptedKeys(fileNameBytes)
                for ((encryptedKey, cause) in deadKeys) {
                    val supersedes = hasRecoveryEntry(encryptedKey)
                    val inPrimary = blobStore.contains(encryptedKey)
                    val inRecovery = encryptedKey in recoveryRecordIds
                    if (inPrimary) {
                        withFailureKind(FailureKind.PRIMARY_REMOVE) {
                            blobStore.delete(encryptedKey, supersedes)
                        }
                    }
                    if (supersedes) {
                        discardRecoveryEntry(encryptedKey)
                    }
                    if ((inPrimary || inRecovery) && (!inRecovery || supersedes)) {
                        removedCount++
                        if (removalCause == null) {
                            removalCause = cause
                        }
                    }
                }
            } finally {
                scanScheduled.set(false)
            }
        }
    }

    private fun CipherProvider.tryDecrypt(
        entry: EntryIndex.Entry,
        operation: FailureOperation,
        action: Map.Entry<String, Action>?,
    ): ByteArray? =
        try {
            decrypt(entry.encryptedValue)
        } catch (e: AEADBadTagException) {
            failureNotifier.notify(e, FailureKind.DECRYPT_VALUE, operation, action)
            unreadableRecords.putIfAbsent(entry.encryptedKey, e)
            scanAndRemoveDeadEntries()
            null
        } catch (e: Exception) {
            failureNotifier.notify(e, FailureKind.DECRYPT_VALUE, operation, action)
            throw e
        }

    private fun decodeKey(entry: EntryIndex.Entry): String? =
        try {
            entries.decodeKey(entry)
        } catch (e: AEADBadTagException) {
            failureNotifier.notify(e, FailureKind.DECRYPT_KEY, FailureOperation.READ)
            synchronized(updateLock) {
                if (entries.remove(entry)) {
                    unreadableRecords[entry.encryptedKey] = e
                }
            }
            scanAndRemoveDeadEntries()
            null
        } catch (e: Exception) {
            failureNotifier.notify(e, FailureKind.DECRYPT_KEY, FailureOperation.READ)
            throw e
        }

    private fun encryptValue(
        value: ByteArray,
        action: Map.Entry<String, Action>,
    ): ByteArray =
        try {
            valueCipherProvider.encrypt(value)
        } catch (e: Exception) {
            failureNotifier.notify(e, FailureKind.ENCRYPT_VALUE, FailureOperation.WRITE, action)
            throw e
        }

    /**
     * Classification travels only through internal persistence catches.
     * Public exceptions stay unchanged.
     */
    private class OperationFailure(val error: Exception, val kind: FailureKind) : Exception(error)

    private fun Exception.originalFailure(): Exception = (this as? OperationFailure)?.error ?: this

    private fun Exception.failureKind(): FailureKind =
        (this as? OperationFailure)?.kind ?: FailureKind.OPERATION

    private inline fun <T> withFailureKind(kind: FailureKind, block: () -> T): T =
        try {
            block()
        } catch (e: OperationFailure) {
            throw e
        } catch (e: Exception) {
            throw OperationFailure(e, kind)
        }

    internal class EncryptedAction(
        override val key: String,
        override val value: Action,
        val encryptedKey: Bytes,
        val encryptedValue: ByteArray?,
    ) : Map.Entry<String, Action>

    internal interface Callback {
        fun onEntryChanged(key: String?)
    }

    internal companion object {

        private const val WRITE_DEBOUNCE_TIMEOUT_MS = 100L

        private const val DEFAULT_BACKOFF_MS = 1000L

        private const val MAX_BACKOFF_MS = 30000L

        fun create(
            context: Context,
            fileName: String,
            keyCipherProvider: CipherProvider,
            valueCipherProvider: CipherProvider,
            ioDispatcher: CoroutineDispatcher,
            recoveryBlobStore: SafeBoxRecoveryBlobStore =
                SafeBoxRecoveryBlobStore.getOrCreate(context),
            failureListener: FailureListener? = null,
        ): SafeBoxEngine {
            val blobStore = SafeBoxBlobStore.create(context, fileName)
            val appOnRPlus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            val appTargetsRPlus = context.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.R
            val failureNotifier = FailureNotifier(fileName, failureListener)
            return SafeBoxEngine(
                blobStore = blobStore,
                recoveryBlobStore = recoveryBlobStore,
                keyCipherProvider = keyCipherProvider,
                valueCipherProvider = valueCipherProvider,
                ioDispatcher = ioDispatcher,
                notifyNullOnClear = appOnRPlus && appTargetsRPlus,
                failureNotifier = failureNotifier,
            )
        }
    }
}
