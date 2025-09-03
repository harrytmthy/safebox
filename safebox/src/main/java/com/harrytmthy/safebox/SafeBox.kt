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
import androidx.annotation.VisibleForTesting
import com.harrytmthy.safebox.SafeBox.Action.Put
import com.harrytmthy.safebox.SafeBox.Action.Remove
import com.harrytmthy.safebox.SafeBox.Companion.create
import com.harrytmthy.safebox.cryptography.ChaCha20CipherProvider
import com.harrytmthy.safebox.cryptography.CipherProvider
import com.harrytmthy.safebox.engine.SafeBoxEngine
import com.harrytmthy.safebox.extensions.toBytes
import com.harrytmthy.safebox.extensions.toEncodedByteArray
import com.harrytmthy.safebox.keystore.SecureRandomKeyProvider
import com.harrytmthy.safebox.state.SafeBoxStateListener
import com.harrytmthy.safebox.storage.Bytes
import com.harrytmthy.safebox.strategy.ValueFallbackStrategy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

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
 * @param engine Internal worker that loads/saves encrypted data and manages runtime state.
 * It also notifies this instance when a key changes so registered listeners get updates.
 */
public class SafeBox private constructor(private val engine: SafeBoxEngine) : SharedPreferences {

    private val listeners = CopyOnWriteArrayList<OnSharedPreferenceChangeListener>()

    private val callback = object : SafeBoxEngine.Callback {
        override fun onEntryChanged(key: String) {
            listeners.forEach { it.onSharedPreferenceChanged(this@SafeBox, key) }
        }
    }

    /**
     * **Deprecated:** SafeBox reads now behave exactly like SharedPreferences, where `getXxx(...)`
     * blocks the current thread until the initial load completes.
     *
     * Sets the fallback behavior when values are accessed before the initial load completes.
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
        engine.setCastFailureStrategy(fallbackStrategy)
    }

    /**
     * **Deprecated:** SafeBox no longer requires instance closing.
     *
     * Immediately closes the underlying file channel and releases resources.
     */
    @Deprecated(message = "This method is now a no-op. Will be removed in v1.3.")
    public fun close() {
        // no-op
    }

    /**
     * **Deprecated:** SafeBox no longer requires instance closing.
     *
     * Closes the underlying file channel only after all pending writes have completed.
     */
    @Deprecated(message = "This method is now a no-op. Will be removed in v1.3.")
    public fun closeWhenIdle() {
        // no-op
    }

    override fun getAll(): Map<String, Any?> =
        engine.getEntries()

    override fun getString(key: String, defValue: String?): String? =
        engine.getValue(key, defValue)

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        engine.getValue(key, defValues)

    override fun getInt(key: String, defValue: Int): Int =
        engine.getValue(key, defValue)

    override fun getLong(key: String, defValue: Long): Long =
        engine.getValue(key, defValue)

    override fun getFloat(key: String, defValue: Float): Float =
        engine.getValue(key, defValue)

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        engine.getValue(key, defValue)

    override fun contains(key: String): Boolean =
        engine.contains(key)

    override fun edit(): SharedPreferences.Editor = Editor(engine)

    override fun registerOnSharedPreferenceChangeListener(l: OnSharedPreferenceChangeListener) {
        val shouldSetCallback = listeners.isEmpty()
        listeners.add(l)
        if (shouldSetCallback) {
            engine.setCallback(callback)
        }
    }

    override fun unregisterOnSharedPreferenceChangeListener(l: OnSharedPreferenceChangeListener) {
        listeners.remove(l)
        if (listeners.isEmpty()) {
            engine.setCallback(null)
        }
    }

    private class Editor(private val engine: SafeBoxEngine) : SharedPreferences.Editor {

        private val actions = LinkedHashMap<String, Action>()

        private val cleared = AtomicBoolean(false)

        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            apply { actions[key] = value?.toEncodedByteArray()?.toBytes()?.let(::Put) ?: Remove }

        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor =
            apply { actions[key] = values?.toEncodedByteArray()?.toBytes()?.let(::Put) ?: Remove }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor =
            apply { actions[key] = Put(value.toEncodedByteArray().toBytes()) }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor =
            apply { actions[key] = Put(value.toEncodedByteArray().toBytes()) }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
            apply { actions[key] = Put(value.toEncodedByteArray().toBytes()) }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
            apply { actions[key] = Put(value.toEncodedByteArray().toBytes()) }

        override fun remove(key: String): SharedPreferences.Editor =
            apply { actions[key] = Remove }

        override fun clear(): SharedPreferences.Editor =
            apply { cleared.set(true) }

        override fun commit(): Boolean =
            engine.commitBatch(actions, cleared.getAndSet(false))

        override fun apply() {
            engine.applyBatch(actions, cleared.getAndSet(false))
        }
    }

    internal sealed class Action {
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
                stateListener?.let(safeBox.engine::setStateListener)
                return safeBox
            }
            return synchronized(instances) {
                instances[fileName]?.let { safeBox ->
                    stateListener?.let(safeBox.engine::setStateListener)
                    return safeBox
                }
                val engine = SafeBoxEngine.create(
                    context = context,
                    fileName = fileName,
                    keyAlias = keyAlias,
                    valueKeyStoreAlias = valueKeyStoreAlias,
                    aad = fileName.toByteArray(),
                    ioDispatcher = ioDispatcher,
                    stateListener = stateListener,
                )
                SafeBox(engine).also { instances[fileName] = it }
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
                stateListener?.let(safeBox.engine::setStateListener)
                return safeBox
            }
            return synchronized(instances) {
                instances[fileName]?.let { safeBox ->
                    stateListener?.let(safeBox.engine::setStateListener)
                    return safeBox
                }
                val engine = SafeBoxEngine.create(
                    context = context,
                    fileName = fileName,
                    keyCipherProvider = keyCipherProvider,
                    valueCipherProvider = valueCipherProvider,
                    ioDispatcher = ioDispatcher,
                    stateListener = stateListener,
                )
                SafeBox(engine).also { instances[fileName] = it }
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
