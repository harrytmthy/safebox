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
import android.util.Log
import com.harrytmthy.safebox.SafeBox.Action
import com.harrytmthy.safebox.SafeBox.Action.Put
import com.harrytmthy.safebox.SafeBox.Action.Remove
import com.harrytmthy.safebox.cryptography.CipherProvider
import com.harrytmthy.safebox.decoder.ByteDecoder
import com.harrytmthy.safebox.extensions.safeBoxScope
import com.harrytmthy.safebox.extensions.toBytes
import com.harrytmthy.safebox.state.SafeBoxGlobalStateObserver
import com.harrytmthy.safebox.state.SafeBoxState
import com.harrytmthy.safebox.state.SafeBoxState.IDLE
import com.harrytmthy.safebox.state.SafeBoxState.STARTING
import com.harrytmthy.safebox.state.SafeBoxState.WRITING
import com.harrytmthy.safebox.state.SafeBoxStateListener
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.AEADBadTagException

internal class SafeBoxEngine private constructor(
    private val blobStore: SafeBoxBlobStore,
    private val recoveryBlobStore: SafeBoxRecoveryBlobStore,
    private val keyCipherProvider: CipherProvider,
    private val valueCipherProvider: CipherProvider,
    private val ioDispatcher: CoroutineDispatcher,
    private var stateListener: SafeBoxStateListener?,
    private val notifyNullOnClear: Boolean,
) {

    private val entries: MutableMap<Bytes, ByteArray> = ConcurrentHashMap()

    private val recoveryEntries = HashMap<Bytes, ByteArray>()

    private val pendingActions = LinkedHashMap<String, Action>()

    private val pendingClear = AtomicBoolean(false)

    private val byteDecoder = ByteDecoder()

    private val updateLock = Any()

    private val pendingUpdateLock = Any()

    private val concurrentWriteCount = AtomicInteger(0)

    private val initialReadCompleted = CompletableDeferred<Unit>()

    private val writeBarrier = AtomicReference(CompletableDeferred<Unit>().apply { complete(Unit) })

    private val writeMutex = Mutex()

    private val scanScheduled = AtomicBoolean(false)

    private val recoveryScheduled = AtomicBoolean(false)

    private var callback: Callback? = null

    private var writeDebounceJob: Job? = null

    init {
        launchWithStartingState {
            entries += blobStore.loadPersistedEntries()
            recoveryBlobStore.loadPersistedEntries(blobStore.getFileName().toBytes())
                .takeIf { it.isNotEmpty() }
                ?.let {
                    entries += it
                    recoveryEntries += it
                }
        }
    }

    fun setCastFailureStrategy(fallbackStrategy: ValueFallbackStrategy) {
        byteDecoder.setCastFailureStrategy(fallbackStrategy)
    }

    fun setStateListener(stateListener: SafeBoxStateListener?) {
        this.stateListener = stateListener
    }

    fun setCallback(callback: Callback?) {
        this.callback = callback
    }

    fun contains(key: String): Boolean {
        awaitInitialReadBlocking()
        return entries.containsKey(key.toEncryptedKey())
    }

    fun getEntries(): Map<String, Any?> {
        awaitInitialReadBlocking()
        val decryptedEntries = HashMap<String, Any?>(entries.size, 1f)
        for (entry in entries) {
            val key = keyCipherProvider.tryDecrypt(entry.key.value)
                ?.toString(Charsets.UTF_8)
                ?: continue
            val value = valueCipherProvider.tryDecrypt(entry.value) ?: continue
            decryptedEntries[key] = byteDecoder.decodeAny(value)
        }
        return decryptedEntries
    }

    inline fun <reified T> getValue(key: String, defValue: T): T {
        awaitInitialReadBlocking()
        return entries[key.toEncryptedKey()]
            ?.let { valueCipherProvider.tryDecrypt(it) }
            ?.let { byteDecoder.decodeAny(it) as T }
            ?: defValue
    }

    fun commitBatch(actions: LinkedHashMap<String, Action>, cleared: Boolean): Boolean {
        if (actions.isEmpty() && !cleared) {
            return true
        }
        val snapshot = LinkedHashMap(actions)
        actions.clear() // Prevents stale mutations on reused editor instance
        synchronized(updateLock) {
            updateEntries(snapshot, cleared)
        }
        writeDebounceJob?.cancel()
        val (pendingActionsSnapshot, shouldClear) = synchronized(pendingUpdateLock) {
            val pendingSnapshot = LinkedHashMap(pendingActions)
            pendingActions.clear()
            pendingSnapshot += snapshot
            pendingSnapshot to (pendingClear.getAndSet(false) || cleared)
        }
        return launchWriteBlocking {
            applyChanges(pendingActionsSnapshot, shouldClear)
        }
    }

    fun applyBatch(actions: LinkedHashMap<String, Action>, cleared: Boolean) {
        if (actions.isEmpty() && !cleared) {
            return
        }
        val snapshot = LinkedHashMap(actions)
        actions.clear() // Prevents stale mutations on reused editor instance
        synchronized(updateLock) {
            updateEntries(snapshot, cleared)
        }
        synchronized(pendingUpdateLock) {
            if (cleared) {
                pendingActions.clear()
                pendingClear.set(true)
            }
            pendingActions += snapshot
        }
        writeDebounceJob?.cancel()
        writeDebounceJob = safeBoxScope.launch(ioDispatcher) {
            delay(WRITE_DEBOUNCE_TIMEOUT_MS)
        }.apply {
            invokeOnCompletion { e ->
                if (e != null) {
                    return@invokeOnCompletion
                }
                val (pendingActionsSnapshot, shouldClear) = synchronized(pendingUpdateLock) {
                    val snapshot = LinkedHashMap(pendingActions)
                    pendingActions.clear()
                    snapshot to pendingClear.getAndSet(false)
                }
                launchWriteAsync {
                    applyChanges(pendingActionsSnapshot, shouldClear)
                }
            }
        }
    }

    private fun updateEntries(actions: LinkedHashMap<String, Action>, cleared: Boolean) {
        if (cleared) {
            entries.clear()
            if (notifyNullOnClear) {
                callback?.onEntryChanged(null)
            }
        }
        val modifiedKeys = callback?.let { ArrayList<String>(actions.size) }
        for ((key, action) in actions) {
            when (action) {
                is Put -> {
                    val encryptedKey = key.toEncryptedKey()
                    val oldValue = entries[encryptedKey]?.let { valueCipherProvider.tryDecrypt(it) }
                    val newValue = action.encodedValue.value
                    if (!newValue.contentEquals(oldValue)) {
                        entries[encryptedKey] = newValue.let(valueCipherProvider::encrypt)
                        modifiedKeys?.add(key)
                    }
                }
                is Remove -> {
                    if (entries.remove(key.toEncryptedKey()) != null) {
                        modifiedKeys?.add(key)
                    }
                }
            }
        }
        for (index in (modifiedKeys?.size ?: return) - 1 downTo 0) {
            callback?.onEntryChanged(modifiedKeys[index])
        }
    }

    private suspend fun applyChanges(entries: LinkedHashMap<String, Action>, cleared: Boolean) {
        if (cleared) {
            blobStore.deleteAll()
        }
        for ((key, action) in entries) {
            when (action) {
                is Put -> {
                    val encryptedKey = key.toEncryptedKey()
                    val encryptedValue = action.encodedValue.value.let(valueCipherProvider::encrypt)
                    try {
                        blobStore.write(encryptedKey, encryptedValue)
                    } catch (_: Exception) {
                        recoveryBlobStore.write(
                            fileName = blobStore.getFileName().toBytes(),
                            encryptedKey = encryptedKey,
                            encryptedValue = encryptedValue,
                        )
                        recoveryEntries[encryptedKey] = encryptedValue
                    }
                }
                is Remove -> {
                    val encryptedKey = key.toEncryptedKey()
                    if (blobStore.contains(encryptedKey)) {
                        blobStore.delete(encryptedKey)
                    }
                }
            }
        }
    }

    private fun scheduleRecoveryEntriesWrite(recoveryBackoffMs: Long) {
        safeBoxScope.launch(ioDispatcher) {
            delay(recoveryBackoffMs)
        }.invokeOnCompletion {
            val nextBackoffMs = (recoveryBackoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            launchWriteAsync(nextBackoffMs) {
                val fileName = blobStore.getFileName().toBytes()
                val snapshot = ArrayList(recoveryEntries.entries)
                val removedKeys = ArrayList<Bytes>()
                for ((encryptedKey, encryptedValue) in snapshot) {
                    try {
                        blobStore.write(encryptedKey, encryptedValue)
                        recoveryEntries.remove(encryptedKey)
                        removedKeys += encryptedKey
                    } catch (_: Exception) {
                        continue
                    }
                }
                recoveryBlobStore.delete(fileName, *removedKeys.toTypedArray())
            }
        }
    }

    private inline fun launchWithStartingState(crossinline block: suspend () -> Unit) {
        updateState(STARTING)
        safeBoxScope.launch(ioDispatcher) {
            try {
                block()
            } finally {
                initialReadCompleted.complete(Unit)
                if (concurrentWriteCount.get() == 0) {
                    updateState(IDLE)
                }
            }
        }.invokeOnCompletion {
            if (recoveryEntries.isNotEmpty()) {
                recoveryScheduled.set(true)
                scheduleRecoveryEntriesWrite(DEFAULT_BACKOFF_MS)
            }
        }
    }

    private inline fun launchWriteBlocking(crossinline block: suspend () -> Unit): Boolean {
        val currentWriteBarrier = CompletableDeferred<Unit>()
        val previousWriteBarrier = writeBarrier.getAndSet(currentWriteBarrier)
        return runBlocking {
            try {
                withStateTransition(previousWriteBarrier, block)
                true
            } catch (e: Exception) {
                Log.e("SafeBox", "Failed to commit changes.", e)
                false
            } finally {
                currentWriteBarrier.complete(Unit)
                if (recoveryEntries.isNotEmpty() && recoveryScheduled.compareAndSet(false, true)) {
                    scheduleRecoveryEntriesWrite(DEFAULT_BACKOFF_MS)
                }
            }
        }
    }

    private inline fun launchWriteAsync(
        recoveryBackoffMs: Long = DEFAULT_BACKOFF_MS,
        crossinline block: suspend () -> Unit,
    ) {
        val currentWriteBarrier = CompletableDeferred<Unit>()
        val previousWriteBarrier = writeBarrier.getAndSet(currentWriteBarrier)
        safeBoxScope.launch(ioDispatcher) {
            try {
                withStateTransition(previousWriteBarrier, block)
            } catch (e: Exception) {
                Log.e("SafeBox", "Failed to commit changes.", e)
            } finally {
                currentWriteBarrier.complete(Unit)
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

    private suspend inline fun withStateTransition(
        previousWriteBarrier: CompletableDeferred<Unit>,
        crossinline block: suspend () -> Unit,
    ) {
        initialReadCompleted.await()
        if (concurrentWriteCount.incrementAndGet() == 1) {
            updateState(WRITING)
        }
        try {
            previousWriteBarrier.await()
            writeMutex.withLock {
                block()
            }
        } finally {
            if (concurrentWriteCount.decrementAndGet() == 0) {
                updateState(IDLE)
            }
        }
    }

    internal fun awaitInitialReadBlocking() {
        if (!initialReadCompleted.isCompleted) {
            runBlocking { initialReadCompleted.await() }
        }
    }

    internal fun closeBlobStoreChannel() {
        runBlocking {
            initialReadCompleted.await()
            writeBarrier.get().await()
            blobStore.closeWhenIdle()
        }
    }

    private fun updateState(newState: SafeBoxState) {
        stateListener?.onStateChanged(newState)
        SafeBoxGlobalStateObserver.updateState(blobStore.getFileName(), newState)
    }

    private fun scanAndRemoveDeadEntries() {
        if (!scanScheduled.compareAndSet(false, true)) {
            return
        }
        launchWriteAsync {
            try {
                val deadKeys = ArrayList<Bytes>()
                for ((encryptedKey, encryptedValue) in entries) {
                    if (valueCipherProvider.tryDecrypt(encryptedValue) == null) {
                        entries.remove(encryptedKey)
                        deadKeys.add(encryptedKey)
                    }
                }
                if (deadKeys.isNotEmpty()) {
                    blobStore.delete(*deadKeys.toTypedArray())
                }
            } finally {
                scanScheduled.set(false)
            }
        }
    }

    private fun CipherProvider.tryDecrypt(encryptedValue: ByteArray): ByteArray? =
        try {
            decrypt(encryptedValue)
        } catch (e: AEADBadTagException) {
            Log.e("SafeBox", "Decrypt failed due to AEADBadTagException.", e)
            scanAndRemoveDeadEntries()
            null
        }

    private fun String.toEncryptedKey(): Bytes =
        keyCipherProvider.encrypt(this.toByteArray()).toBytes()

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
            stateListener: SafeBoxStateListener?,
        ): SafeBoxEngine {
            val blobStore = SafeBoxBlobStore.create(context, fileName)
            val appOnRPlus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            val appTargetsRPlus = context.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.R
            return SafeBoxEngine(
                blobStore = blobStore,
                recoveryBlobStore = SafeBoxRecoveryBlobStore.getOrCreate(context),
                keyCipherProvider = keyCipherProvider,
                valueCipherProvider = valueCipherProvider,
                ioDispatcher = ioDispatcher,
                stateListener = stateListener,
                notifyNullOnClear = appOnRPlus && appTargetsRPlus,
            )
        }
    }
}
