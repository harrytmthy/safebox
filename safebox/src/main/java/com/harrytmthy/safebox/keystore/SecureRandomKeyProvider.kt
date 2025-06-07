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
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.SecretKey

/**
 * A [KeyProvider] implementation that manages a securely generated symmetric key.
 *
 * Behavior:
 * - The key is generated using [SecureRandomProvider] on first use.
 * - It is encrypted via the given [CipherProvider] and stored in noBackupFilesDir.
 * - The key is decrypted and cached on first access.
 *
 * This class is suitable for non-Keystore-based algorithms like ChaCha20, where
 * secure persistence must be handled outside AndroidKeyStore.
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

    /**
     * Retrieves or generates the symmetric key, decrypting from disk if necessary.
     * The key is cached in memory for a short period before being cleared.
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
            return secretKey
        }
    }

    override fun destroyKey() {
        decryptedKey.get()?.destroy()
        decryptedKey.set(null)
    }

    private fun createNewKey(): ByteArray {
        val generatedKey = SecureRandomProvider.generate(keySize)
        val encryptedKey = cipherProvider.encrypt(generatedKey)
        encryptedKeyBytes = encryptedKey
        encryptedKeyFile.writeBytes(encryptedKey)
        return generatedKey
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
            val file = File(context.noBackupFilesDir, "$fileName.bin")
            if (!file.exists()) {
                file.createNewFile()
            }
            return SecureRandomKeyProvider(file, keySize, algorithm, cipherProvider)
        }
    }
}
