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

package com.harrytmthy.safebox.cryptography

import android.security.keystore.KeyProperties.BLOCK_MODE_GCM
import android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE
import android.security.keystore.KeyProperties.KEY_ALGORITHM_AES
import android.security.keystore.KeyProperties.PURPOSE_DECRYPT
import android.security.keystore.KeyProperties.PURPOSE_ENCRYPT
import com.harrytmthy.safebox.extensions.requireAes
import com.harrytmthy.safebox.keystore.AndroidKeyStoreKeyProvider
import com.harrytmthy.safebox.keystore.KeyProvider
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * AES cipher provider using GCM mode for authenticated encryption.
 *
 * This implementation provides encryption and decryption via AES-GCM, where confidentiality
 * and integrity are both preserved using symmetric key encryption and authentication tag.
 *
 * A random IV is generated for each encryption call and prepended to the result.
 * AAD (Additional Authenticated Data) can be optionally used to bind external data
 * to the encryption for integrity verification.
 */
internal class AesGcmCipherProvider private constructor(
    private val keyProvider: KeyProvider,
    private val aad: ByteArray?,
) : CipherProvider {

    private val cipher = Cipher.getInstance(TRANSFORMATION)

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val key = keyProvider.getOrCreateKey()
        requireAes(key)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        aad?.let(cipher::updateAAD)
        val iv = cipher.iv
        val actualData = cipher.doFinal(plaintext)
        return iv + actualData
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        val key = keyProvider.getOrCreateKey()
        requireAes(key)
        val iv = ciphertext.copyOfRange(0, IV_SIZE)
        val actualData = ciphertext.copyOfRange(IV_SIZE, ciphertext.size)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        aad?.let(cipher::updateAAD)
        return cipher.doFinal(actualData)
    }

    internal companion object {

        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        private const val IV_SIZE = 12

        private const val GCM_TAG_LENGTH_BITS = 128

        internal fun create(alias: String, aad: ByteArray?): CipherProvider {
            val provider = AndroidKeyStoreKeyProvider(
                alias = alias,
                algorithm = KEY_ALGORITHM_AES,
                purposes = PURPOSE_ENCRYPT or PURPOSE_DECRYPT,
                parameterSpecBuilder = {
                    setBlockModes(BLOCK_MODE_GCM)
                    setEncryptionPaddings(ENCRYPTION_PADDING_NONE)
                },
            )
            return AesGcmCipherProvider(provider, aad)
        }
    }
}
