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

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(AndroidJUnit4::class)
class SafeBoxCryptoTest {

    @Test
    fun decrypt_shouldReturnOriginalText() {
        val originalText = "SafeBoxCrypto works! 😂"
        val secret = SafeBoxCrypto.createSecret()
        val encryptedText = SafeBoxCrypto.encrypt(originalText, secret)

        val text = SafeBoxCrypto.decrypt(encryptedText, secret)

        assertEquals(originalText, text)
    }

    @Test
    fun decryptOrNull_shouldReturnOriginalText() {
        val originalText = "SafeBoxCrypto works! 😂"
        val secret = SafeBoxCrypto.createSecret()
        val encryptedText = SafeBoxCrypto.encryptOrNull(originalText, secret).orEmpty()

        val text = SafeBoxCrypto.decryptOrNull(encryptedText, secret)

        assertEquals(originalText, text)
    }

    @Test
    fun decrypt_bytes_shouldReturnOriginalBytes() {
        // Some pseudo-random payload
        val original = ByteArray(4096) { i -> (i * 31 and 0xFF).toByte() }
        val secret = SafeBoxCrypto.createSecretBytes()
        val encrypted = SafeBoxCrypto.encrypt(original, secret)

        val decrypted = SafeBoxCrypto.decrypt(encrypted, secret)

        assertContentEquals(original, decrypted)
    }

    @Test
    fun decryptOrNull_bytes_shouldReturnOriginalBytes() {
        val original = "binary-\uD83D\uDE80".toByteArray(Charsets.UTF_8)
        val secret = SafeBoxCrypto.createSecretBytes()
        val encrypted = SafeBoxCrypto.encryptOrNull(original, secret)
        assertNotNull(encrypted)

        val decrypted = SafeBoxCrypto.decryptOrNull(encrypted, secret)

        assertNotNull(decrypted)
        assertContentEquals(original, decrypted)
    }
}
