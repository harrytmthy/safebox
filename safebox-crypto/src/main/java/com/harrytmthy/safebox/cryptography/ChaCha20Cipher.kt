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

import android.annotation.SuppressLint
import android.security.keystore.KeyProperties.DIGEST_SHA256
import com.harrytmthy.safebox.keystore.SafeSecretKey
import org.bouncycastle.jcajce.spec.AEADParameterSpec
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.SecretKey

/**
 * A ChaCha20-Poly1305 [SafeCipher] that uses BouncyCastle.
 *
 * IV strategy:
 *  - If `deterministic == true`, IV = SHA-256(plaintext). This intentionally leaks plaintext
 *    equality and is only appropriate for encrypting *keys* (e.g. preference keys).
 *  - Otherwise IV is random per call (recommended for values).
 *
 * **Note:** Android ships an outdated BouncyCastle. Newer releases include modern algorithms.
 * Suppressing the deprecation warning is required to reference the provider name.
 *
 * **Output framing:** IV (12 bytes) || ciphertext || tag.
 */
@SuppressLint("DeprecatedProvider")
internal class ChaCha20Cipher(private val deterministic: Boolean) : SafeCipher {

    private val cipherLock = Any()

    private val cipher by lazy {
        try {
            Cipher.getInstance(TRANSFORMATION, BouncyCastleProvider.PROVIDER_NAME)
        } catch (_: GeneralSecurityException) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.addProvider(BouncyCastleProvider())
            Cipher.getInstance(TRANSFORMATION, BouncyCastleProvider.PROVIDER_NAME)
        }
    }

    override fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray =
        synchronized(cipherLock) {
            val iv = if (deterministic) {
                MessageDigest.getInstance(DIGEST_SHA256).digest(plaintext).copyOf(IV_SIZE)
            } else {
                SecureRandomProvider.generate(IV_SIZE)
            }
            val paramSpec = AEADParameterSpec(iv, MAC_SIZE_BITS)
            cipher.init(Cipher.ENCRYPT_MODE, key, paramSpec)
            val encrypted = cipher.doFinal(plaintext)
            (key as? SafeSecretKey)?.releaseHeapCopy()
            iv + encrypted
        }

    override fun decrypt(ciphertext: ByteArray, key: SecretKey): ByteArray =
        synchronized(cipherLock) {
            val iv = ciphertext.copyOfRange(0, IV_SIZE)
            val actual = ciphertext.copyOfRange(IV_SIZE, ciphertext.size)
            val paramSpec = AEADParameterSpec(iv, MAC_SIZE_BITS)
            cipher.init(Cipher.DECRYPT_MODE, key, paramSpec)
            val plaintext = cipher.doFinal(actual)
            (key as? SafeSecretKey)?.releaseHeapCopy()
            plaintext
        }

    internal companion object {
        internal const val ALGORITHM = "ChaCha20"
        internal const val KEY_SIZE = 32
        internal const val TRANSFORMATION = "ChaCha20-Poly1305"
        private const val IV_SIZE = 12
        private const val MAC_SIZE_BITS = 128
    }
}
