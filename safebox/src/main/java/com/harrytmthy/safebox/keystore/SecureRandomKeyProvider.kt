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
import android.util.Log
import com.harrytmthy.safebox.cryptography.CipherProvider
import com.harrytmthy.safebox.cryptography.SecureRandomProvider
import java.io.File
import java.security.GeneralSecurityException
import javax.crypto.SecretKey

/**
 * A [KeyProvider] implementation that manages a securely generated symmetric key.
 *
 * Behavior:
 * - The key is generated using [SecureRandomProvider] on first use.
 * - It is encrypted via the given [CipherProvider] and stored in [noBackupFilesDir].
 * - The key is decrypted and cached on first access, and may be manually cleared.
 *
 * This class is suitable for non-Keystore-based algorithms like ChaCha20,
 * where secure persistence must be handled outside AndroidKeyStore.
 */
internal class SecureRandomKeyProvider private constructor(
    private val keyFile: File,
    private val keySize: Int,
    private val algorithm: String,
    private val cipherProvider: CipherProvider,
) : KeyProvider {

    private var keyBytes: ByteArray? = try {
        keyFile.readBytes().takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        Log.e("SafeBox", "SecureRandomKeyProvider failed to read bytes.", e)
        null
    }

    private var decryptedKey: SecretKey? = null

    /**
     * Retrieves or generates the symmetric key, decrypting from disk if necessary.
     * The key is cached in memory for a short period before being cleared.
     */
    override fun getOrCreateKey(): SecretKey =
        synchronized(LOCK) {
            decryptedKey?.let { return it }
            val key = try {
                keyBytes?.takeIf { it.isNotEmpty() }
                    ?.let(cipherProvider::decrypt)
            } catch (e: GeneralSecurityException) {
                Log.e("SafeBox", e.message, e)
                null
            } ?: createNewKey()
            val secretKey = key.toSecretKey()
            decryptedKey = secretKey
            return secretKey
        }

    private fun createNewKey(): ByteArray {
        val generatedKey = SecureRandomProvider.generate(keySize)
        val encryptedKey = cipherProvider.encrypt(generatedKey)
        keyBytes = encryptedKey
        keyFile.writeBytes(encryptedKey)
        return generatedKey
    }

    private fun ByteArray.toSecretKey(): SecretKey =
        SafeSecretKey(this, algorithm)

    internal companion object {

        private val LOCK = Any()

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
