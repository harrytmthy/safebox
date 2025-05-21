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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harrytmthy.safebox.keystore.SecureRandomKeyProvider
import org.junit.After
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

@RunWith(AndroidJUnit4::class)
class ChaCha20CipherProviderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val keyProvider = SecureRandomKeyProvider.create(
        context = context,
        fileName = "test-key",
        keySize = 32,
        algorithm = "ChaCha20",
        cipherProvider = AesGcmCipherProvider.create("test-gcm-alias", "test".toByteArray()),
    )

    private val cipherProvider = ChaCha20CipherProvider(keyProvider)

    @After
    fun tearDown() {
        File(context.noBackupFilesDir, "test-key.bin").delete()
    }

    @Test
    fun encryptAndDecrypt_shouldReturnOriginal() {
        val input = "SafeBox with ChaCha20".toByteArray()

        val encrypted = cipherProvider.encrypt(input)
        val encrypted2 = cipherProvider.encrypt(input)
        val decrypted = cipherProvider.decrypt(encrypted)

        val first = encrypted.copyOfRange(12, encrypted.size)
        val second = encrypted2.copyOfRange(12, encrypted2.size)
        assertContentEquals(first, second)
        assertContentEquals(encrypted, encrypted2)
        assertContentEquals(input, decrypted)
    }

    @Test
    fun decrypt_withModifiedCipher_shouldFail() {
        val input = "Do not tamper!".toByteArray()

        val tampered = cipherProvider.encrypt(input)
            .also { it[13] = (it[13].toInt() xor 0xFF).toByte() }

        assertFailsWith<Exception> { cipherProvider.decrypt(tampered) }
    }
}
