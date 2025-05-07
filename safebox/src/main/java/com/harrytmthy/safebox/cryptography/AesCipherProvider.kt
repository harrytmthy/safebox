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

import com.harrytmthy.safebox.exception.SafeBoxDecryptionException
import com.harrytmthy.safebox.extensions.requireAes
import com.harrytmthy.safebox.mode.AesMode
import com.harrytmthy.safebox.mode.AesMode.Gcm
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey

/**
 * AES cipher provider supporting both CBC and GCM block modes.
 *
 * Uses a supplied [AesMode] to dynamically configure encryption and decryption behavior.
 * GCM mode includes optional AAD binding for integrity protection.
 *
 * @param aesMode The selected AES block mode (CBC or GCM).
 */
public class AesCipherProvider(private val aesMode: AesMode) : CipherProvider {

    override fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
        requireAes(key)

        val iv = IvProvider.generate(aesMode.ivSize)
        val cipher = Cipher.getInstance(aesMode.transformation)
        val spec = aesMode.getParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        if (aesMode is Gcm) {
            aesMode.aad?.let(cipher::updateAAD)
        }
        val actualData = cipher.doFinal(plaintext)
        return iv + actualData
    }

    override fun decrypt(ciphertext: ByteArray, key: SecretKey): ByteArray {
        requireAes(key)

        val iv = ciphertext.copyOfRange(0, aesMode.ivSize)
        val actualData = ciphertext.copyOfRange(aesMode.ivSize, ciphertext.size)
        return try {
            val cipher = Cipher.getInstance(aesMode.transformation)
            val spec = aesMode.getParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            if (aesMode is Gcm) {
                aesMode.aad?.let(cipher::updateAAD)
            }
            cipher.doFinal(actualData)
        } catch (_: GeneralSecurityException) {
            throw SafeBoxDecryptionException()
        }
    }
}
