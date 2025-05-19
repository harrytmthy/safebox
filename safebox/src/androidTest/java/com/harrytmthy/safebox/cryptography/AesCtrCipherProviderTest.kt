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
import kotlin.test.Test
import kotlin.test.assertContentEquals

@RunWith(AndroidJUnit4::class)
class AesCtrCipherProviderTest {

    @Test
    fun encryptAndDecrypt_withCtr_shouldReturnOriginalPlaintext() {
        val provider = AesCtrCipherProvider.create("test-ctr-alias")
        val plaintext = "This is CTR from AndroidKeyStore".toByteArray()

        val encrypted = provider.encrypt(plaintext)
        val decrypted = provider.decrypt(encrypted)

        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun encrypt_withSameInput_shouldProduceSameOutput() {
        val provider = AesCtrCipherProvider.create("deterministic-alias")
        val input = "Deterministic input".toByteArray()

        val first = provider.encrypt(input)
        val second = provider.encrypt(input)

        assertContentEquals(first, second)
    }
}
