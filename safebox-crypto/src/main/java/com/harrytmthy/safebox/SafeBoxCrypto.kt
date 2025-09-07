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

package com.harrytmthy.safebox

import android.security.keystore.KeyProperties.DIGEST_SHA256
import android.util.Base64
import android.util.Log
import com.harrytmthy.safebox.SafeBoxCrypto.decrypt
import com.harrytmthy.safebox.SafeBoxCrypto.encrypt
import com.harrytmthy.safebox.cryptography.ChaCha20Cipher
import com.harrytmthy.safebox.cryptography.SafeCipher
import com.harrytmthy.safebox.cryptography.SecureRandomProvider
import com.harrytmthy.safebox.keystore.SafeSecretKey
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.SecretKey

/**
 * Simple crypto helper backed by ChaCha20-Poly1305.
 *
 * Inputs and outputs are strings:
 *  - The secret is a 32-byte key encoded as Base64 URL safe.
 *  - The ciphertext is also Base64 URL safe.
 *  - Plaintext is encoded and decoded as UTF-8.
 *
 * Nonce strategy:
 *  - Uses a random 12-byte nonce per call. Output framing is IV || ciphertext || tag.
 *
 * This helper does not use Android Keystore and does not persist keys.
 * Keep the secret safe and supply the same secret to decrypt.
 */
object SafeBoxCrypto {

    private val safeCipher: SafeCipher = ChaCha20Cipher(deterministic = false)

    /**
     * Creates a new 32-byte secret and returns it as Base64 URL safe.
     *
     * Anyone with this secret can decrypt data encrypted with it.
     */
    fun createSecret(): String {
        val secretBytes = SecureRandomProvider.generate(ChaCha20Cipher.KEY_SIZE)
        return encodeBase64(secretBytes)
    }

    /**
     * Encrypts [text] using [secret].
     *
     * - [text] is encoded as UTF-8.
     * - [secret] must be Base64 URL safe representing a 32-byte key.
     *
     * @return Ciphertext as Base64 URL safe.
     * @throws GeneralSecurityException if encryption fails or inputs are malformed.
     */
    fun encrypt(text: String, secret: String): String {
        val plaintext = text.toByteArray(Charsets.UTF_8)
        val key = decodeBase64(secret).toSecretKey()
        val ciphertext = safeCipher.encrypt(plaintext, key)
        return encodeBase64(ciphertext)
    }

    /**
     * Decrypts [encryptedText] using [secret] and returns the original UTF-8 string.
     *
     * - [encryptedText] must be Base64 URL safe.
     * - [secret] must be Base64 URL safe representing a 32-byte key.
     *
     * @return The original text.
     * @throws GeneralSecurityException if decryption fails or inputs are malformed.
     */
    fun decrypt(encryptedText: String, secret: String): String {
        val ciphertext = decodeBase64(encryptedText)
        val key = decodeBase64(secret).toSecretKey()
        val plaintext = safeCipher.decrypt(ciphertext, key)
        return plaintext.toString(Charsets.UTF_8)
    }

    /**
     * Encrypts like [encrypt] but returns null on failure.
     */
    fun encryptOrNull(text: String, secret: String): String? =
        try {
            encrypt(text, secret)
        } catch (e: GeneralSecurityException) {
            Log.e("SafeBoxCrypto", "Failed to encrypt text.", e)
            null
        }

    /**
     * Decrypts like [decrypt] but returns null on failure.
     */
    fun decryptOrNull(text: String, secret: String): String? =
        try {
            decrypt(text, secret)
        } catch (e: GeneralSecurityException) {
            Log.e("SafeBoxCrypto", "Failed to decrypt text.", e)
            null
        }

    private fun encodeBase64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)

    private fun decodeBase64(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP or Base64.URL_SAFE)

    private fun ByteArray.toSecretKey(): SecretKey {
        val mask = MessageDigest.getInstance(DIGEST_SHA256).digest(this)
        return SafeSecretKey(
            key = this,
            mask = mask,
            algorithm = ChaCha20Cipher.ALGORITHM,
        )
    }
}
