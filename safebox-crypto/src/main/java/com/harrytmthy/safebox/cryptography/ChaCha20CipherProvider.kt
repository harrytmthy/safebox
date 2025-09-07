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

import com.harrytmthy.safebox.keystore.KeyProvider

/**
 * ChaCha20-Poly1305 [CipherProvider] backed by a [KeyProvider].
 *
 * This is a thin adapter that:
 *  1) Obtains a DEK from [keyProvider], then
 *  2) Delegates crypto operations to an internal [ChaCha20Cipher].
 *
 *  **Output framing:** IV (12 bytes) || ciphertext || tag.
 */
internal class ChaCha20CipherProvider(
    private val keyProvider: KeyProvider,
    deterministic: Boolean,
) : CipherProvider {

    val chaCha20CipherProvider = ChaCha20Cipher(deterministic)

    override fun encrypt(plaintext: ByteArray): ByteArray =
        chaCha20CipherProvider.encrypt(plaintext, keyProvider.getOrCreateKey())

    override fun decrypt(ciphertext: ByteArray): ByteArray =
        chaCha20CipherProvider.decrypt(ciphertext, keyProvider.getOrCreateKey())
}
