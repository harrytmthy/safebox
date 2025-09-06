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

package com.harrytmthy.safebox.keystore

import android.content.Context
import android.security.keystore.KeyProperties.DIGEST_SHA256
import android.util.Log
import com.harrytmthy.safebox.cryptography.CipherProvider
import com.harrytmthy.safebox.cryptography.SecureRandomProvider
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.SecretKey

/**
 * A [KeyProvider] that manages a securely generated symmetric key (DEK).
 *
 * Behavior:
 * - The key is generated using [SecureRandomProvider] on first use.
 * - It is wrapped via the given [cipherProvider] and stored on disk.
 * - On access, the wrapped key is unwrapped and returned as a [SafeSecretKey], which
 *   keeps the material masked in off-heap memory.
 * - The unwrapped key instance is cached to avoid repeated unwraps. Call [rotateKey]
 *   to replace it or [destroyKey] to wipe it from memory.
 *
 * This class is suitable for non-Keystore algorithms (e.g. ChaCha20) where persistence
 * must be handled outside AndroidKeyStore.
 */
internal class SecureRandomKeyProvider private constructor(
    private val encryptedKeyFile: File,
    private val keySize: Int,
    private val algorithm: String,
    private val cipherProvider: CipherProvider,
) : KeyProvider {

    private var encryptedKeyBytes: ByteArray = encryptedKeyFile.readBytes()

    private val decryptedKey = AtomicReference<SecretKey>()

    private val lock = Any()

    init {
        if (cipherProvider.shouldRotateKey() && encryptedKeyBytes.isNotEmpty()) {
            try {
                val key = cipherProvider.decrypt(encryptedKeyBytes)
                cipherProvider.rotateKey()
                val encryptedKey = cipherProvider.encrypt(key)
                replaceFileAtomically(encryptedKey)
                encryptedKeyBytes = encryptedKey
            } catch (e: Exception) {
                Log.e("SafeBox", "Failed to rewrap DEK.", e)
            }
        }
    }

    /**
     * Retrieves or generates the DEK.
     *
     * No long-lived raw heap array is kept. Temporary heap copies produced by JCE
     * are cleared via [SafeSecretKey.releaseHeapCopy] right after cipher operations.
     */
    override fun getOrCreateKey(): SecretKey {
        decryptedKey.get()?.let { return it }
        return synchronized(lock) {
            decryptedKey.get()?.let { return it } // fast path after lock acquisition
            val key = try {
                encryptedKeyBytes.takeIf { it.isNotEmpty() }
                    ?.let(cipherProvider::decrypt)
            } catch (e: GeneralSecurityException) {
                Log.e("SafeBox", e.message, e)
                null
            } ?: createNewKey()
            val secretKey = key.toSecretKey()
            decryptedKey.set(secretKey)
            secretKey
        }
    }

    override fun rotateKey() {
        val newKey = createNewKey().toSecretKey()
        decryptedKey.set(newKey)
    }

    override fun destroyKey() {
        decryptedKey.get()?.destroy()
        decryptedKey.set(null)
    }

    private fun createNewKey(): ByteArray {
        val generatedKey = SecureRandomProvider.generate(keySize)
        val encryptedKey = cipherProvider.encrypt(generatedKey)
        replaceFileAtomically(encryptedKey)
        encryptedKeyBytes = encryptedKey
        return generatedKey
    }

    private fun replaceFileAtomically(encryptedKey: ByteArray) {
        val directory = encryptedKeyFile.parentFile ?: error("Missing parent directory")
        val temporaryFile = File(directory, encryptedKeyFile.name + ".tmp")
        try {
            temporaryFile.outputStream().use { outputStream ->
                outputStream.write(encryptedKey)
                outputStream.fd.sync()
            }
            if (temporaryFile.renameTo(encryptedKeyFile)) {
                return
            }
            encryptedKeyFile.delete()
            if (!temporaryFile.renameTo(encryptedKeyFile)) {
                error("Failed to persist wrapped DEK atomically.")
            }
        } finally {
            temporaryFile.delete()
        }
    }

    private fun ByteArray.toSecretKey(): SecretKey {
        val mask = MessageDigest.getInstance(DIGEST_SHA256).digest(encryptedKeyBytes)
        return SafeSecretKey(this, mask, algorithm)
    }

    internal companion object {

        internal fun create(
            context: Context,
            fileName: String,
            keySize: Int,
            algorithm: String,
            cipherProvider: CipherProvider,
        ): SecureRandomKeyProvider {
            val file = getEncryptedKeyFile(context, fileName)
            return SecureRandomKeyProvider(file, keySize, algorithm, cipherProvider)
        }

        private fun getEncryptedKeyFile(context: Context, fileName: String): File {
            val oldDestination = File(context.noBackupFilesDir, "SafeBoxKey.bin")
            val newDestination = File(context.noBackupFilesDir, "$fileName.key.bin")
            if (newDestination.exists() && newDestination.length() > 0L) {
                return newDestination
            }
            newDestination.createNewFile()
            if (!oldDestination.exists()) {
                return newDestination
            }
            try {
                oldDestination.inputStream().use { input ->
                    newDestination.outputStream().use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
            } catch (e: IOException) {
                Log.e("SafeBox", "Failed to migrate wrapped DEK.", e)
                newDestination.delete()
                return oldDestination
            }
            oldDestination.delete()
            return newDestination
        }
    }
}
