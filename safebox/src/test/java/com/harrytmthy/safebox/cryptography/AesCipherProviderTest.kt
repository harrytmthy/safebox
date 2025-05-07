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
import com.harrytmthy.safebox.mode.AesMode
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class AesCipherProviderTest {

    private val aesKey: SecretKey = generateAesKey()

    private val aad = "SafeBoxAAD".toByteArray()

    @Test
    fun `CBC encrypt-decrypt returns original plaintext`() {
        val provider = AesCipherProvider(AesMode.Cbc("PKCS5Padding"))
        val plaintext = "Hello CBC".toByteArray()
        val encrypted = provider.encrypt(plaintext, aesKey)
        val decrypted = provider.decrypt(encrypted, aesKey)
        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun `GCM encrypt-decrypt with AAD returns original plaintext`() {
        val provider = AesCipherProvider(AesMode.Gcm(aad))
        val plaintext = "Hello GCM".toByteArray()
        val encrypted = provider.encrypt(plaintext, aesKey)
        val decrypted = provider.decrypt(encrypted, aesKey)
        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun `GCM decrypt with tampered ciphertext throws SafeBoxDecryptionException`() {
        val provider = AesCipherProvider(AesMode.Gcm(aad))
        val plaintext = "Tamper me".toByteArray()
        val encrypted = provider.encrypt(plaintext, aesKey)
        val tampered = encrypted.copyOf()
        tampered[4] = (tampered[4].toInt() xor 0xFF).toByte()
        assertFailsWith<SafeBoxDecryptionException> {
            provider.decrypt(tampered, aesKey)
        }
    }

    @Test
    fun `encrypt with non-AES key throws`() {
        val provider = AesCipherProvider(AesMode.Cbc("PKCS5Padding"))
        val fakeKey = generateFakeKey()
        assertFailsWith<IllegalArgumentException> {
            provider.encrypt("bad".toByteArray(), fakeKey)
        }
    }

    @Test
    fun `decrypt with non-AES key throws`() {
        val provider = AesCipherProvider(AesMode.Cbc("PKCS5Padding"))
        val fakeKey = generateFakeKey()
        val data = provider.encrypt("hello".toByteArray(), aesKey)
        assertFailsWith<IllegalArgumentException> {
            provider.decrypt(data, fakeKey)
        }
    }

    private fun generateAesKey(): SecretKey = KeyGenerator.getInstance("AES")
        .apply { init(256) }
        .generateKey()

    private fun generateFakeKey(): SecretKey = SecretKeySpec(ByteArray(16), "Blowfish")
}
