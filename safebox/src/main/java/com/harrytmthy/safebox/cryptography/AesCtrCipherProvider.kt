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

import android.security.keystore.KeyProperties.BLOCK_MODE_CTR
import android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE
import android.security.keystore.KeyProperties.KEY_ALGORITHM_AES
import android.security.keystore.KeyProperties.PURPOSE_DECRYPT
import android.security.keystore.KeyProperties.PURPOSE_ENCRYPT
import com.harrytmthy.safebox.extensions.requireAes
import com.harrytmthy.safebox.keystore.AndroidKeyStoreKeyProvider
import com.harrytmthy.safebox.keystore.KeyProvider
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec

/**
 * AES cipher provider using CTR mode for fast, deterministic encryption.
 *
 * This implementation is designed for internal key encoding in SafeBox, here high throughput and
 * deterministic output are prioritized over authentication.
 *
 * Note: CTR mode does not provide integrity or authentication. Do not use this provider for
 * value encryption. It is only suitable for key encoding.
 */
internal class AesCtrCipherProvider private constructor(
    fixedIv: ByteArray,
    private val keyProvider: KeyProvider,
) : CipherProvider {

    private val cipher = Cipher.getInstance(TRANSFORMATION)

    private val spec = IvParameterSpec(fixedIv)

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val key = keyProvider.getOrCreateKey()
        requireAes(key)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        return cipher.doFinal(plaintext)
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        val key = keyProvider.getOrCreateKey()
        requireAes(key)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return cipher.doFinal(ciphertext)
    }

    internal companion object {

        private const val TRANSFORMATION = "AES/CTR/NoPadding"

        internal fun create(alias: String, iv: ByteArray = ByteArray(16) { 0 }): CipherProvider {
            val keyProvider = AndroidKeyStoreKeyProvider(
                alias = alias,
                algorithm = KEY_ALGORITHM_AES,
                purposes = PURPOSE_ENCRYPT or PURPOSE_DECRYPT,
                parameterSpecBuilder = {
                    setBlockModes(BLOCK_MODE_CTR)
                    setEncryptionPaddings(ENCRYPTION_PADDING_NONE)
                    setRandomizedEncryptionRequired(false)
                },
            )
            return AesCtrCipherProvider(iv, keyProvider)
        }
    }
}
