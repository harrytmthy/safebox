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

package com.harrytmthy.safebox.factory

import android.content.Context
import com.harrytmthy.safebox.cryptography.AesGcmCipherProvider
import com.harrytmthy.safebox.cryptography.ChaCha20CipherProvider
import com.harrytmthy.safebox.cryptography.CipherProvider
import com.harrytmthy.safebox.keystore.SecureRandomKeyProvider

/**
 * A factory for constructing the cryptography stack used by SafeBox.
 * Implementation classes remain internal to the crypto module.
 *
 * This factory returns a pair of [CipherProvider] instances, one for keys and one for values.
 */
object SafeBoxCryptoFactory {

    /**
     * Creates a pair of ChaCha20-Poly1305 providers that share one data-encryption key (DEK):
     *
     * - **First [CipherProvider]**: Uses a deterministic IV, intended for encrypting keys.
     * - **Second [CipherProvider]**: Uses a randomized IV, intended for encrypting values.
     *
     * DEK management:
     * - The DEK is generated with secure random and persisted on disk.
     * - The DEK is wrapped by a separate keystore-backed key and bound to [fileName] via AAD.
     * - The same DEK instance is shared by both providers to avoid redundant key material.
     *
     * @param context Context used for key storage locations and Android Keystore access.
     * @param fileName SafeBox fileName. Also used as AAD for KEK wrapping and DEK file naming.
     * @return A [Pair] of deterministic cipher (for keys) and randomized cipher (for values).
     */
    fun createChaCha20Providers(
        context: Context,
        fileName: String,
    ): Pair<CipherProvider, CipherProvider> {
        val aesGcmCipherProvider = AesGcmCipherProvider.create(aad = fileName.toByteArray())
        val keyCipherKeyProvider = SecureRandomKeyProvider.create(
            context = context,
            fileName = fileName,
            keySize = ChaCha20CipherProvider.KEY_SIZE,
            algorithm = ChaCha20CipherProvider.ALGORITHM,
            cipherProvider = aesGcmCipherProvider,
        )
        val valueCipherKeyProvider = SecureRandomKeyProvider.create(
            context = context,
            fileName = fileName,
            keySize = ChaCha20CipherProvider.KEY_SIZE,
            algorithm = ChaCha20CipherProvider.ALGORITHM,
            cipherProvider = aesGcmCipherProvider,
        )
        val keyCipher = ChaCha20CipherProvider(keyCipherKeyProvider, deterministic = true)
        val valueCipher = ChaCha20CipherProvider(valueCipherKeyProvider, deterministic = false)
        return keyCipher to valueCipher
    }
}
