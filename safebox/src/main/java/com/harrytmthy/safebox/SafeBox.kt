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

package com.harrytmthy.safebox

import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.harrytmthy.safebox.SafeBox.Action.Put
import com.harrytmthy.safebox.SafeBox.Action.Remove
import com.harrytmthy.safebox.SafeBox.Companion.create
import com.harrytmthy.safebox.cryptography.AesGcmCipherProvider
import com.harrytmthy.safebox.cryptography.ChaCha20CipherProvider
import com.harrytmthy.safebox.cryptography.CipherProvider
import com.harrytmthy.safebox.decoder.ByteDecoder
import com.harrytmthy.safebox.extensions.safeBoxScope
import com.harrytmthy.safebox.extensions.toBytes
import com.harrytmthy.safebox.extensions.toEncodedByteArray
import com.harrytmthy.safebox.keystore.SecureRandomKeyProvider
import com.harrytmthy.safebox.state.SafeBoxStateListener
import com.harrytmthy.safebox.state.SafeBoxStateManager
import com.harrytmthy.safebox.storage.Bytes
import com.harrytmthy.safebox.storage.SafeBoxBlobStore
import com.harrytmthy.safebox.strategy.ValueFallbackStrategy
import com.harrytmthy.safebox.strategy.ValueFallbackStrategy.WARN
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.AEADBadTagException

/**
 * SafeBox provides secure, high-performance key-value storage designed as a drop-in replacement
 * for [SharedPreferences], featuring automatic encryption, fast memory-mapped storage, and
 * built-in security best practices.
 *
 * Key features:
 * - **Dual encryption providers:** Uses separate ciphers for key encryption (deterministic) and
 *   value encryption (non-deterministic) for maximum integrity and confidentiality.
 * - **Memory-safe:** Employs strategies like DirectByteBuffer to prevent secret leakage into heap.
 * - **Performance-oriented:** Provides significantly faster read and write operations than
 *   traditional `EncryptedSharedPreferences`.
 * - **Robust storage:** Uses memory-mapped files with safe concurrent read/write capabilities.
 *
 * Instances should always be obtained using the static [create] methods, which handle secure
 * initialization and configuration.
 *
 * @param blobStore Internal storage engine managing encrypted key-value pairs.
 * @param keyCipherProvider Cipher used for encrypting and decrypting keys (deterministic).
 * @param valueCipherProvider Cipher used for encrypting and decrypting values (randomized).
 * @param stateManager Responsible for managing SafeBox lifecycle states and its concurrency.
 */
public class SafeBox private constructor(
    private val blobStore: SafeBoxBlobStore,
    private val keyCipherProvider: CipherProvider,
    private val valueCipherProvider: CipherProvider,
    private val stateManager: SafeBoxStateManager,
) : SharedPreferences {

    private val entries: MutableMap<Bytes, ByteArray> = ConcurrentHashMap()

    private val castFailureStrategy = AtomicReference(WARN)

    private val byteDecoder = ByteDecoder(castFailureStrategy::get)

    private val listeners = CopyOnWriteArrayList<OnSharedPreferenceChangeListener>()

    private val scanScheduled = AtomicBoolean(false)

    private val delegate = object : Delegate {

        private val updateLock = Any()

        override fun commit(entries: LinkedHashMap<String, Action>, cleared: Boolean): Boolean {
            val entriesToWrite = LinkedHashMap(entries)
            synchronized(updateLock) {
                entries.clear() // Prevents stale mutations on reused editor instance
                updateEntries(entriesToWrite, cleared)
            }
            return stateManager.launchCommitWithWritingState {
                applyChanges(entriesToWrite, cleared)
            }
        }

        override fun apply(entries: LinkedHashMap<String, Action>, cleared: Boolean) {
            val entriesToWrite = LinkedHashMap(entries)
            synchronized(updateLock) {
                entries.clear() // Prevents stale mutations on reused editor instance
                updateEntries(entriesToWrite, cleared)
            }
            stateManager.launchApplyWithWritingState {
                applyChanges(entriesToWrite, cleared)
            }
        }

        private fun updateEntries(entries: LinkedHashMap<String, Action>, cleared: Boolean) {
            if (cleared) {
                val keys = this@SafeBox.entries.keys.toHashSet()
                this@SafeBox.entries.clear()
                for (encryptedKey in keys) {
                    val key = keyCipherProvider.tryDecrypt(encryptedKey.value)
                        ?.toString(Charsets.UTF_8)
                        ?: continue
                    listeners.forEach { it.onSharedPreferenceChanged(this@SafeBox, key) }
                }
            }
            for ((key, action) in entries) {
                when (action) {
                    is Put -> {
                        val encryptedKey = key.toEncryptedKey()
                        val encryptedValue = action.encodedValue.value
                            .let(valueCipherProvider::encrypt)
                        this@SafeBox.entries[encryptedKey] = encryptedValue
                    }
                    is Remove -> {
                        val encryptedKey = key.toEncryptedKey()
                        this@SafeBox.entries.remove(encryptedKey)
                    }
                }
                listeners.forEach { it.onSharedPreferenceChanged(this@SafeBox, key) }
            }
        }
    }

    init {
        stateManager.launchWithStartingState {
            entries += blobStore.loadPersistedEntries()
        }
    }

    /**
     * **Deprecated:** SafeBox reads now behave exactly like SharedPreferences, where `getXxx(...)`
     * blocks the current thread until the initial load completes.
     *
     * Sets the fallback behavior when values are accessed before the initial load completes.
     *
     * SafeBox performs a background load of previously written entries on initialization.
     * By default, if a `getXxx(...)` call is made before this is complete, a warning is logged.
     *
     * Use this to configure whether such access should be warned, or result in an error.
     *
     * @param fallbackStrategy The behavior to apply when access is premature
     */
    @Deprecated(message = "This method is now a no-op. Will be removed in v1.3.")
    public fun setInitialLoadStrategy(fallbackStrategy: ValueFallbackStrategy) {
        // no-op
    }

    /**
     * Sets the fallback behavior when a stored value cannot be cast to the expected type.
     *
     * For example, calling `getInt("some_key")` on a value originally written as a String
     * will trigger the strategy defined here.
     *
     * By default, the operation logs a warning and returns the default value.
     *
     * @param fallbackStrategy The behavior to apply on decoding type mismatch
     */
    public fun setCastFailureStrategy(fallbackStrategy: ValueFallbackStrategy) {
        castFailureStrategy.set(fallbackStrategy)
    }

    /**
     * **Deprecated:** SafeBox no longer requires instance closing.
     *
     * Immediately closes the underlying file channel and releases resources.
     *
     * ⚠️ Once closed, this instance becomes *permanently unusable*. Any further access will fail.
     *
     * ⚠️ Only use this method when you're certain that no writes are in progress.
     *
     * Closing during an active write can result in data corruption or incomplete persistence.
     */
    @Deprecated(message = "This method is now a no-op. Will be removed in v1.3.")
    public fun close() {
        // no-op
    }

    /**
     * **Deprecated:** SafeBox no longer requires instance closing.
     *
     * Closes the underlying file channel only after all pending writes have completed.
     *
     * ⚠️ Once closed, this instance becomes *permanently unusable*. Any further access will fail.
     *
     * ✅ This is the recommended way to dispose of SafeBox in async environments.
     *
     * Internally, this launches a coroutine on [safeBoxScope] to wait until the SafeBox
     * becomes idle before releasing resources.
     */
    @Deprecated(message = "This method is now a no-op. Will be removed in v1.3.")
    public fun closeWhenIdle() {
        // no-op
    }

    override fun getAll(): Map<String, Any?> {
        stateManager.awaitInitialReadBlocking()
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

    override fun getString(key: String, defValue: String?): String? =
        getDecryptedValue(key)
            ?.let(byteDecoder::decodeString)
            ?: defValue

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        getDecryptedValue(key)
            ?.let(byteDecoder::decodeStringSet)
            ?: defValues

    override fun getInt(key: String, defValue: Int): Int =
        getDecryptedValue(key)
            ?.let(byteDecoder::decodeInt)
            ?: defValue

    override fun getLong(key: String, defValue: Long): Long =
        getDecryptedValue(key)
            ?.let(byteDecoder::decodeLong)
            ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        getDecryptedValue(key)
            ?.let(byteDecoder::decodeFloat)
            ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        getDecryptedValue(key)
            ?.let(byteDecoder::decodeBoolean)
            ?: defValue

    override fun contains(key: String): Boolean {
        stateManager.awaitInitialReadBlocking()
        return entries.containsKey(key.toEncryptedKey())
    }

    override fun edit(): SharedPreferences.Editor = Editor(delegate)

    override fun registerOnSharedPreferenceChangeListener(
        listener: OnSharedPreferenceChangeListener,
    ) {
        listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: OnSharedPreferenceChangeListener,
    ) {
        listeners.remove(listener)
    }

    private fun getDecryptedValue(key: String): ByteArray? {
        stateManager.awaitInitialReadBlocking()
        return entries[key.toEncryptedKey()]
            ?.let { valueCipherProvider.tryDecrypt(it) }
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
                    blobStore.write(encryptedKey, encryptedValue)
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

    private fun scanAndRemoveDeadEntries() {
        if (!scanScheduled.compareAndSet(false, true)) {
            return
        }
        stateManager.launchApplyWithWritingState {
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

    private class Editor(private val delegate: Delegate) : SharedPreferences.Editor {

        private val entries = LinkedHashMap<String, Action>()

        private val cleared = AtomicBoolean(false)

        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            apply { entries[key] = value?.toEncodedByteArray()?.toBytes()?.let(::Put) ?: Remove }

        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor =
            apply { entries[key] = values?.toEncodedByteArray()?.toBytes()?.let(::Put) ?: Remove }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor =
            apply { entries[key] = Put(value.toEncodedByteArray().toBytes()) }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor =
            apply { entries[key] = Put(value.toEncodedByteArray().toBytes()) }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
            apply { entries[key] = Put(value.toEncodedByteArray().toBytes()) }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
            apply { entries[key] = Put(value.toEncodedByteArray().toBytes()) }

        override fun remove(key: String): SharedPreferences.Editor =
            apply { entries[key] = Remove }

        /**
         * Marks the editor to perform a full `clear()` before applying other mutations.
         * This will notify listeners of all removed keys, followed by any modifications.
         */
        override fun clear(): SharedPreferences.Editor =
            apply { cleared.set(true) }

        override fun commit(): Boolean = delegate.commit(entries, cleared.get())

        override fun apply() {
            delegate.apply(entries, cleared.get())
        }
    }

    private interface Delegate {
        fun commit(entries: LinkedHashMap<String, Action>, cleared: Boolean): Boolean
        fun apply(entries: LinkedHashMap<String, Action>, cleared: Boolean)
    }

    private sealed class Action {
        data class Put(val encodedValue: Bytes) : Action()
        object Remove : Action()
    }

    public companion object {

        @VisibleForTesting
        internal const val DEFAULT_KEY_ALIAS = "SafeBoxKey"

        internal const val DEFAULT_VALUE_KEYSTORE_ALIAS = "SafeBoxValue"

        @VisibleForTesting
        internal val instances = ConcurrentHashMap<String, SafeBox>()

        /**
         * Creates a [SafeBox] instance with secure defaults:
         * - Keys are deterministically encrypted using [ChaCha20CipherProvider].
         * - Values are encrypted with the same ChaCha20 key, using a randomized IV per encryption.
         * - The ChaCha20 secret is encrypted with AES-GCM via [SecureRandomKeyProvider].
         *
         * This method is idempotent per [fileName]. Repeated calls return the existing instance.
         * If [stateListener] is non-null, it replaces the current listener. All other parameters
         * are ignored when the instance already exists.
         *
         * @param context The application context
         * @param fileName The name of the backing file used for persistence
         * @param keyAlias The identifier for storing the key (used to locate its encrypted form)
         * @param valueKeyStoreAlias The Android Keystore alias used for AES-GCM key generation
         * @param ioDispatcher The dispatcher used for I/O operations (default: [Dispatchers.IO])
         * @param stateListener The listener to observe instance-bound state transitions
         *
         * @return A fully configured [SafeBox] instance
         */
        @JvmOverloads
        @JvmStatic
        @Throws(IllegalStateException::class)
        public fun create(
            context: Context,
            fileName: String,
            keyAlias: String = DEFAULT_KEY_ALIAS,
            valueKeyStoreAlias: String = DEFAULT_VALUE_KEYSTORE_ALIAS,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            stateListener: SafeBoxStateListener? = null,
        ): SafeBox {
            instances[fileName]?.let { safeBox ->
                stateListener?.let(safeBox.stateManager::setStateListener)
                return safeBox
            }
            return synchronized(instances) {
                instances[fileName]?.let { safeBox ->
                    stateListener?.let(safeBox.stateManager::setStateListener)
                    return safeBox
                }
                val aesGcmCipherProvider = AesGcmCipherProvider.create(
                    alias = valueKeyStoreAlias,
                    aad = fileName.toByteArray(),
                )
                val keyProvider = SecureRandomKeyProvider.create(
                    context = context,
                    fileName = fileName,
                    keyAlias = keyAlias,
                    keySize = ChaCha20CipherProvider.KEY_SIZE,
                    algorithm = ChaCha20CipherProvider.ALGORITHM,
                    cipherProvider = aesGcmCipherProvider,
                )
                val keyCipherProvider = ChaCha20CipherProvider(keyProvider, deterministic = true)
                val valueCipherProvider = ChaCha20CipherProvider(keyProvider, deterministic = false)
                val stateManager = SafeBoxStateManager(fileName, stateListener, ioDispatcher)
                val blobStore = SafeBoxBlobStore.create(context, fileName)
                SafeBox(blobStore, keyCipherProvider, valueCipherProvider, stateManager)
                    .also { instances[fileName] = it }
            }
        }

        /**
         * **Deprecation:** SafeBox no longer supports custom AAD. Changing it can cause MAC check
         * failures and dead entries. Consequently, `additionalAuthenticatedData` is ignored, and
         * this overload will be removed in v1.3. `keyAlias` and `valueKeyStoreAlias` are planned
         * for deprecation in v1.3.
         *
         * Creates a [SafeBox] instance with secure defaults:
         * - Keys are deterministically encrypted using [ChaCha20CipherProvider].
         * - Values are encrypted with the same ChaCha20 key, but with randomized IV per encryption.
         * - The ChaCha20 secret is encrypted using AES-GCM via [SecureRandomKeyProvider].
         *
         * This method handles secure initialization and is recommended for most use cases.
         *
         * **Common pitfalls:**
         * - Do NOT create multiple SafeBox instances with the same file name.
         * - Avoid scoping SafeBox to short-lived components (e.g. ViewModel).
         *
         * @param context The application context
         * @param fileName The name of the backing file used for persistence
         * @param keyAlias The identifier for storing the key (used to locate its encrypted form)
         * @param valueKeyStoreAlias The Android Keystore alias used for AES-GCM key generation
         * @param additionalAuthenticatedData Optional AAD bound to the AES-GCM (default: fileName)
         * @param ioDispatcher The dispatcher used for I/O operations (default: [Dispatchers.IO])
         * @param stateListener The listener to observe instance-bound state transitions
         *
         * @return A fully configured [SafeBox] instance
         * @throws IllegalStateException if the file is already registered.
         */
        @Deprecated("additionalAuthenticatedData is ignored. Use the overload without it.")
        @JvmOverloads
        @JvmStatic
        @Throws(IllegalStateException::class)
        public fun create(
            context: Context,
            fileName: String,
            keyAlias: String = DEFAULT_KEY_ALIAS,
            valueKeyStoreAlias: String = DEFAULT_VALUE_KEYSTORE_ALIAS,
            additionalAuthenticatedData: ByteArray,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            stateListener: SafeBoxStateListener? = null,
        ): SafeBox =
            create(context, fileName, keyAlias, valueKeyStoreAlias, ioDispatcher, stateListener)

        /**
         * Creates a [SafeBox] instance using a custom [CipherProvider] implementation.
         *
         * This method is idempotent per [fileName]. Repeated calls return the existing instance.
         * If [stateListener] is non-null, it replaces the current listener. All other parameters
         * are ignored when the instance already exists.
         *
         * This is useful for testing or advanced use cases where you want to control
         * the encryption mechanism directly.
         *
         * @param context The application context
         * @param fileName The name of the backing file used for persistence
         * @param keyCipherProvider Cipher used for encrypting and decrypting keys
         * @param valueCipherProvider Cipher used for encrypting and decrypting values
         * @param ioDispatcher The dispatcher used for I/O operations (default: [Dispatchers.IO])
         * @param stateListener The listener to observe instance-bound state transitions
         *
         * @return A [SafeBox] instance with the provided [CipherProvider]
         */
        @JvmOverloads
        @JvmStatic
        @Throws(IllegalStateException::class)
        public fun create(
            context: Context,
            fileName: String,
            keyCipherProvider: CipherProvider,
            valueCipherProvider: CipherProvider,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            stateListener: SafeBoxStateListener? = null,
        ): SafeBox {
            instances[fileName]?.let { safeBox ->
                stateListener?.let(safeBox.stateManager::setStateListener)
                return safeBox
            }
            return synchronized(instances) {
                instances[fileName]?.let { safeBox ->
                    stateListener?.let(safeBox.stateManager::setStateListener)
                    return safeBox
                }
                val stateManager = SafeBoxStateManager(fileName, stateListener, ioDispatcher)
                val blobStore = SafeBoxBlobStore.create(context, fileName)
                SafeBox(blobStore, keyCipherProvider, valueCipherProvider, stateManager)
                    .also { instances[fileName] = it }
            }
        }

        /**
         * Returns the previously created instance for [fileName].
         *
         * @return The existing [SafeBox] instance.
         * @throws IllegalStateException if [create] has not been called for this file.
         */
        @JvmStatic
        public fun get(fileName: String): SafeBox =
            instances[fileName] ?: error("SafeBox '$fileName' is not initialized.")

        /**
         * Returns the previously created instance for [fileName], or null if not initialized.
         *
         * @return The existing [SafeBox] instance, or null.
         */
        @JvmStatic
        public fun getOrNull(fileName: String): SafeBox? = instances[fileName]
    }
}
