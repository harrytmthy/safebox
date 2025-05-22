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
import com.harrytmthy.safebox.storage.Bytes
import com.harrytmthy.safebox.storage.SafeBoxBlobStore
import com.harrytmthy.safebox.strategy.ValueFallbackStrategy
import com.harrytmthy.safebox.strategy.ValueFallbackStrategy.WARN
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * SafeBox provides secure, high-performance key-value storage designed as a drop-in replacement
 * for [SharedPreferences], featuring automatic encryption, fast memory-mapped storage, and
 * built-in security best practices.
 *
 * Key features:
 * - **Automatic encryption:** Utilizes ChaCha20-Poly1305 for secure storage and AES-GCM for
 *   protecting internal keys.
 * - **Memory-safe:** Employs strategies like DirectByteBuffer to prevent secret leakage into heap.
 * - **Performance-oriented:** Provides significantly faster read and write operations than
 *   traditional `EncryptedSharedPreferences`.
 * - **Robust storage:** Uses memory-mapped files with safe concurrent read/write capabilities.
 *
 * Instances should always be obtained using the static [create] methods, which handle secure
 * initialization and configuration.
 *
 * @param blobStore Internal storage engine managing encrypted key-value pairs.
 * @param cipherProvider Cipher provider responsible for encryption/decryption operations.
 * @param dispatcher Coroutine dispatcher for IO operations, defaults to [Dispatchers.IO].
 */
public class SafeBox private constructor(
    private val blobStore: SafeBoxBlobStore,
    private val cipherProvider: CipherProvider,
    private val dispatcher: CoroutineDispatcher,
) : SharedPreferences {

    private val castFailureStrategy = AtomicReference<ValueFallbackStrategy>(WARN)

    private val byteDecoder = ByteDecoder(castFailureStrategy::get)

    private val listeners = CopyOnWriteArrayList<OnSharedPreferenceChangeListener>()

    private val commitMutex = Mutex()

    private val applyMutex = Mutex()

    @Volatile
    private var applyCompleted = CompletableDeferred<Unit>().apply { complete(Unit) }

    private val delegate = object : Delegate {

        private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            Log.e("SafeBox", "Failed to apply changes.", throwable)
            applyCompleted.complete(Unit)
        }

        override fun commit(entries: LinkedHashMap<String, Action>, cleared: Boolean): Boolean =
            runBlocking {
                try {
                    applyCompleted.await()
                    commitMutex.withLock {
                        applyChanges(entries, cleared)
                    }
                    true
                } catch (e: Exception) {
                    Log.e("SafeBox", "Failed to commit changes.", e)
                    false
                }
            }

        override fun apply(entries: LinkedHashMap<String, Action>, cleared: Boolean) {
            safeBoxScope.launch(dispatcher + exceptionHandler) {
                applyCompleted = CompletableDeferred()
                applyMutex.withLock {
                    applyChanges(entries, cleared)
                }
                applyCompleted.complete(Unit)
            }
        }
    }

    /**
     * Sets the fallback behavior when values are accessed before the initial load completes.
     *
     * SafeBox performs a background load of previously written entries on initialization.
     * By default, if a `getXxx(...)` call is made before this is complete, a warning is logged.
     *
     * Use this to configure whether such access should be warned, or result in an error.
     *
     * @param fallbackStrategy The behavior to apply when access is premature
     */
    public fun setInitialLoadStrategy(fallbackStrategy: ValueFallbackStrategy) {
        blobStore.setInitialLoadStrategy(fallbackStrategy)
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
     * Releases resources and closes the underlying file channel.
     *
     * Call this when SafeBox is no longer in use to ensure file handles are cleaned up.
     * Failing to do so may result in resource leaks or file corruption.
     */
    public fun close() {
        blobStore.close()
    }

    override fun getAll(): Map<String, Any?> {
        val encryptedEntries = blobStore.getAll()
        val decryptedEntries = HashMap<String, Any?>(encryptedEntries.size, 1f)
        for (entry in encryptedEntries) {
            val key = cipherProvider.decrypt(entry.key.value).toString(Charsets.UTF_8)
            val value = cipherProvider.decrypt(entry.value)
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

    override fun contains(key: String): Boolean =
        blobStore.contains(key.toEncryptedKey())

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

    private fun getDecryptedValue(key: String): ByteArray? =
        blobStore.get(key.toEncryptedKey())
            ?.let(cipherProvider::decrypt)

    private suspend fun applyChanges(entries: LinkedHashMap<String, Action>, cleared: Boolean) {
        if (cleared) {
            blobStore.deleteAll().forEach { encryptedKey ->
                val key = cipherProvider.decrypt(encryptedKey.value).toString(Charsets.UTF_8)
                listeners.forEach { it.onSharedPreferenceChanged(this, key) }
            }
        }
        for ((key, action) in entries) {
            when (action) {
                is Put -> {
                    val encryptedKey = key.toEncryptedKey()
                    val encryptedValue = action.encodedValue.value.let(cipherProvider::encrypt)
                    blobStore.write(encryptedKey, encryptedValue)
                }
                is Remove -> {
                    val encryptedKey = key.toEncryptedKey()
                    if (blobStore.contains(encryptedKey)) {
                        blobStore.delete(encryptedKey)
                    }
                }
            }
            listeners.forEach { it.onSharedPreferenceChanged(this, key) }
        }
        entries.clear()
    }

    private fun String.toEncryptedKey(): Bytes =
        cipherProvider.encrypt(this.toByteArray()).toBytes()

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

        @VisibleForTesting
        internal const val DEFAULT_VALUE_KEYSTORE_ALIAS = "SafeBoxValue"

        /**
         * Creates a [SafeBox] instance with secure defaults:
         * - Keys are deterministically encrypted using [ChaCha20CipherProvider]
         * - The ChaCha20 secret is encrypted using AES-GCM and stored in an encrypted file
         * - Values are encrypted with the same ChaCha20 key
         *
         * This method handles secure initialization and is recommended for most use cases.
         *
         * @param context The application context
         * @param fileName The name of the backing file used for persistence
         * @param keyAlias The identifier for storing the key (used to locate its encrypted form)
         * @param valueKeyStoreAlias The Android Keystore alias used for AES-GCM key generation
         * @param additionalAuthenticatedData Optional AAD bound to the AES-GCM (default: fileName)
         * @param ioDispatcher The dispatcher used for I/O operations (default: [Dispatchers.IO])
         *
         * @return A fully configured [SafeBox] instance
         */
        @JvmOverloads
        @JvmStatic
        public fun create(
            context: Context,
            fileName: String,
            keyAlias: String = DEFAULT_KEY_ALIAS,
            valueKeyStoreAlias: String = DEFAULT_VALUE_KEYSTORE_ALIAS,
            additionalAuthenticatedData: ByteArray = fileName.toByteArray(),
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): SafeBox {
            val keyCipherProvider = AesGcmCipherProvider.create(
                alias = valueKeyStoreAlias,
                aad = additionalAuthenticatedData,
            )
            val keyProvider = SecureRandomKeyProvider.create(
                context = context,
                fileName = keyAlias,
                keySize = ChaCha20CipherProvider.KEY_SIZE,
                algorithm = ChaCha20CipherProvider.ALGORITHM,
                cipherProvider = keyCipherProvider,
            )
            val cipherProvider = ChaCha20CipherProvider(keyProvider)
            return create(context, fileName, cipherProvider, ioDispatcher)
        }

        /**
         * Creates a [SafeBox] instance using a custom [CipherProvider] implementation.
         *
         * This is useful for testing or advanced use cases where you want to control
         * the encryption mechanism directly.
         *
         * @param context The application context
         * @param fileName The name of the backing file used for persistence
         * @param deterministicCipherProvider The cipher used to encrypt both keys and values
         * @param ioDispatcher The dispatcher used for I/O operations (default: [Dispatchers.IO])
         *
         * @return A [SafeBox] instance with the provided [CipherProvider]
         */
        @JvmOverloads
        @JvmStatic
        public fun create(
            context: Context,
            fileName: String,
            deterministicCipherProvider: CipherProvider,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): SafeBox {
            val blobStore = SafeBoxBlobStore.create(context, fileName, ioDispatcher)
            return SafeBox(blobStore, deterministicCipherProvider, ioDispatcher)
        }
    }
}
