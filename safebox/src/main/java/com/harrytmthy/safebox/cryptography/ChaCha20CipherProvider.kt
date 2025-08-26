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
import com.harrytmthy.safebox.keystore.KeyProvider
import com.harrytmthy.safebox.keystore.SafeSecretKey
import org.bouncycastle.jcajce.spec.AEADParameterSpec
import java.security.MessageDigest
import javax.crypto.Cipher

/**
 * ChaCha20-Poly1305 cipher provider using a 256-bit symmetric key. IV is randomly generated
 * per encryption and prepended to the ciphertext.
 *
 * Android includes an outdated version of BouncyCastle which only provides old algorithms,
 * and deprecates it. However, the newer versions contain modern algorithms (e.g. ChaCha20)
 * that is safe. Thus, suppressing the deprecation is necessary.
 */
@SuppressLint("DeprecatedProvider")
internal class ChaCha20CipherProvider(
    private val keyProvider: KeyProvider,
    private val deterministic: Boolean,
) : CipherProvider {

    private val cipherPool = SingletonCipherPoolProvider.getChaCha20CipherPool()

    private val cipherLock = Any()

    override fun encrypt(plaintext: ByteArray): ByteArray =
        synchronized(cipherLock) {
            val iv = if (deterministic) {
                MessageDigest.getInstance(DIGEST_SHA256).digest(plaintext).copyOf(IV_SIZE)
            } else {
                SecureRandomProvider.generate(IV_SIZE)
            }
            val paramSpec = AEADParameterSpec(iv, MAC_SIZE_BITS)
            val key = keyProvider.getOrCreateKey()
            val encrypted = cipherPool.withCipher { cipher ->
                cipher.init(Cipher.ENCRYPT_MODE, key, paramSpec)
                cipher.doFinal(plaintext)
            }
            (key as? SafeSecretKey)?.releaseHeapCopy()
            iv + encrypted
        }

    override fun decrypt(ciphertext: ByteArray): ByteArray =
        synchronized(cipherLock) {
            val iv = ciphertext.copyOfRange(0, IV_SIZE)
            val actual = ciphertext.copyOfRange(IV_SIZE, ciphertext.size)
            val paramSpec = AEADParameterSpec(iv, MAC_SIZE_BITS)
            val key = keyProvider.getOrCreateKey()
            val plaintext = cipherPool.withCipher { cipher ->
                cipher.init(Cipher.DECRYPT_MODE, key, paramSpec)
                cipher.doFinal(actual)
            }
            (key as? SafeSecretKey)?.releaseHeapCopy()
            plaintext
        }

    override fun destroyKey() {
        keyProvider.destroyKey()
    }

    internal companion object {
        internal const val ALGORITHM = "ChaCha20"
        internal const val KEY_SIZE = 32
        internal const val TRANSFORMATION = "ChaCha20-Poly1305"
        private const val IV_SIZE = 12
        private const val MAC_SIZE_BITS = 128
    }
}
