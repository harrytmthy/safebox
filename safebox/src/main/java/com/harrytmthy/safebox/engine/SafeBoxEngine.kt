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
import java.util.Collections
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

    private val unreadableKeys = Collections.newSetFromMap(ConcurrentHashMap<Bytes, Boolean>())

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
                try {
                    entries.load(encryptedKey, encryptedValue)
                } catch (e: AEADBadTagException) {
                    // TODO: Report initialization key failures in the diagnostics follow-up.
                    unreadableKeys.add(encryptedKey)
                } catch (e: Exception) {
                    // TODO: Classify initialization key failures in the diagnostics follow-up.
                    throw e
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
            val value = valueCipherProvider.tryDecrypt(entry.encryptedValue) ?: continue
            decryptedEntries[key] = byteDecoder.decodeAny(value)
        }
        return decryptedEntries
    }

    inline fun <reified T> getValue(key: String, defValue: T): T {
        awaitInitialReadBlocking()
        return entries[key]
            ?.let { valueCipherProvider.tryDecrypt(it.encryptedValue) }
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
                    failureNotifier.notify(e, FailureKind.ENCRYPT_KEY, entry)
                    throw e
                }
                when (action) {
                    is Put -> {
                        val oldEncryptedValue = lookup.entry?.encryptedValue
                        val oldValue = oldEncryptedValue?.let {
                            valueCipherProvider.tryDecrypt(it, action = entry)
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
            blobStore.deleteAll(supersedes)
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
                        blobStore.write(encryptedKey, encryptedValue, supersedes)
                    } catch (e: Exception) {
                        failureNotifier.notify(e, FailureKind.PRIMARY_WRITE, entry)
                        recoveryBlobStore.write(
                            fileName = fileNameBytes,
                            encryptedKey = encryptedKey,
                            encryptedValue = encryptedValue,
                            forceNow = false,
                        )
                        // Keep mapped recovery data tracked even if its flush fails.
                        recoveryEntries[encryptedKey] = encryptedValue
                        recoveryBlobStore.flushPendingChanges()
                        continue
                    }
                    if (supersedes) {
                        discardRecoveryEntry(encryptedKey)
                    }
                }
                is Remove -> {
                    val supersedes = hasRecoveryEntry(encryptedKey)
                    if (blobStore.contains(encryptedKey)) {
                        blobStore.delete(encryptedKey, supersedes)
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
        recoveryBlobStore.delete(fileNameBytes, encryptedKey)
        recoveryEntries.remove(encryptedKey)
    }

    private suspend fun discardRecoveryEntries() {
        for (entry in recoveryEntries.entries) {
            entry.setValue(null)
        }
        recoveryBlobStore.delete(fileNameBytes)
        recoveryEntries.clear()
    }

    private fun scheduleRecoveryEntriesWrite(recoveryBackoffMs: Long) {
        safeBoxScope.launch(ioDispatcher) {
            delay(recoveryBackoffMs)
        }.invokeOnCompletion {
            val nextBackoffMs = (recoveryBackoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            launchWriteAsync(nextBackoffMs, kind = FailureKind.REPLAY) {
                replayRecoveryEntries()
            }
        }
    }

    @VisibleForTesting
    internal suspend fun replayRecoveryEntries() {
        recoveryBlobStore.flushPendingChanges()
        val snapshot = ArrayList(recoveryEntries.entries)
        val replayedKeys = ArrayList<Bytes>()
        for ((encryptedKey, encryptedValue) in snapshot) {
            if (encryptedValue == null) {
                discardRecoveryEntry(encryptedKey)
                continue
            }
            try {
                blobStore.write(encryptedKey, encryptedValue, false)
                replayedKeys += encryptedKey
            } catch (e: Exception) {
                failureNotifier.notify(e, FailureKind.REPLAY)
                continue
            }
        }
        if (replayedKeys.isEmpty()) {
            return
        }
        blobStore.flushDirtyPages()
        for (encryptedKey in replayedKeys) {
            recoveryEntries[encryptedKey] = null
        }
        recoveryBlobStore.delete(fileNameBytes, *replayedKeys.toTypedArray())
        for (encryptedKey in replayedKeys) {
            recoveryEntries.remove(encryptedKey)
        }
    }

    private suspend fun loadStartingEntries(): Map<Bytes, ByteArray> {
        val loadedEntries = LinkedHashMap(blobStore.loadPersistedEntries())
        val recoveredEntries = recoveryBlobStore.loadPersistedEntries(fileNameBytes)
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
                failureNotifier.notify(e, FailureKind.LOAD)
            } finally {
                initialReadCompleted.complete(Unit)
            }
        }.invokeOnCompletion {
            if (unreadableKeys.isNotEmpty()) {
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
                failureNotifier.notifyBatch(e, FailureKind.WRITE, actions, cleared)
                false
            }
            try {
                blobStore.flushDirtyPages()
            } catch (e: Exception) {
                failureNotifier.notifyBatch(e, FailureKind.FLUSH, actions, cleared)
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
        kind: FailureKind = FailureKind.WRITE,
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
                failureNotifier.notifyBatch(e, kind, actions, cleared)
            } finally {
                try {
                    blobStore.flushDirtyPages()
                } catch (e: Exception) {
                    failureNotifier.notifyBatch(e, FailureKind.FLUSH, actions, cleared)
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
        launchWriteAsync(kind = FailureKind.REMOVE_UNREADABLE) {
            try {
                val deadKeys = LinkedHashSet<Bytes>()
                synchronized(updateLock) {
                    val currentRecordIds = entries.values().mapTo(HashSet()) { it.encryptedKey }
                    for (encryptedKey in unreadableKeys.toList()) {
                        if (encryptedKey !in currentRecordIds) {
                            deadKeys.add(encryptedKey)
                        }
                        unreadableKeys.remove(encryptedKey)
                    }
                }
                for (entry in entries.values()) {
                    if (valueCipherProvider.tryDecrypt(entry.encryptedValue, notify = false) == null) {
                        synchronized(updateLock) {
                            if (entries.remove(entry)) {
                                deadKeys.add(entry.encryptedKey)
                            }
                        }
                    }
                }
                // TODO: Report cleanup outcomes in the diagnostics follow-up.
                for (encryptedKey in deadKeys) {
                    val supersedes = hasRecoveryEntry(encryptedKey)
                    if (blobStore.contains(encryptedKey)) {
                        blobStore.delete(encryptedKey, supersedes)
                    }
                    if (supersedes) {
                        discardRecoveryEntry(encryptedKey)
                    }
                }
            } finally {
                scanScheduled.set(false)
            }
        }
    }

    private fun CipherProvider.tryDecrypt(
        encryptedValue: ByteArray,
        notify: Boolean = true,
        action: Map.Entry<String, Action>? = null,
    ): ByteArray? =
        try {
            decrypt(encryptedValue)
        } catch (e: AEADBadTagException) {
            failureNotifier.notify(e, FailureKind.DECRYPT, action, notifyListener = notify)
            scanAndRemoveDeadEntries()
            null
        } catch (e: Exception) {
            failureNotifier.notify(e, FailureKind.DECRYPT, action, notifyListener = notify)
            throw e
        }

    private fun decodeKey(entry: EntryIndex.Entry): String? =
        try {
            entries.decodeKey(entry)
        } catch (e: AEADBadTagException) {
            failureNotifier.notify(e, FailureKind.DECRYPT)
            synchronized(updateLock) {
                if (entries.remove(entry)) {
                    unreadableKeys.add(entry.encryptedKey)
                }
            }
            scanAndRemoveDeadEntries()
            null
        } catch (e: Exception) {
            failureNotifier.notify(e, FailureKind.DECRYPT)
            throw e
        }

    private fun encryptValue(
        value: ByteArray,
        action: Map.Entry<String, Action>,
    ): ByteArray =
        try {
            valueCipherProvider.encrypt(value)
        } catch (e: Exception) {
            failureNotifier.notify(e, FailureKind.ENCRYPT_VALUE, action)
            throw e
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
