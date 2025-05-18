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

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

@RunWith(AndroidJUnit4::class)
class AesGcmCipherProviderTest {

    @Test
    fun encryptAndDecrypt_withGCM_shouldReturnOriginalPlaintext() {
        val aad = "AndroidAAD".toByteArray()
        val provider = AesGcmCipherProvider.create("test-gcm-alias", aad)
        val plaintext = "This is GCM from AndroidKeyStore".toByteArray()

        val encrypted = provider.encrypt(plaintext)
        val decrypted = provider.decrypt(encrypted)

        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun decrypt_withTamperedCipherText_shouldThrowAEADBadTagException() {
        val aad = "TamperAAD".toByteArray()
        val provider = AesGcmCipherProvider.create("tamper-alias", aad)
        val plaintext = "Don't tamper me".toByteArray()
        val encrypted = provider.encrypt(plaintext)

        val tampered = encrypted.copyOf().apply { this[4] = (this[4].toInt() xor 0xFF).toByte() }

        assertFailsWith<AEADBadTagException> { provider.decrypt(tampered) }
    }
}
