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

package com.harrytmthy.safebox.verifier

import com.harrytmthy.safebox.exception.SafeBoxDecryptionException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DefaultMacIntegrityVerifierTest {

    private val key: SecretKey = generateAesKey()

    @Test
    fun `attachMac should return data plus mac tag`() {
        val data = "SafeBox rocks".toByteArray()
        val result = DefaultMacIntegrityVerifier.attachMac(key, data)
        assertTrue(result.size > data.size)
    }

    @Test
    fun `verifyMac should return original data if mac is valid`() {
        val data = "Integrity is everything".toByteArray()
        val secured = DefaultMacIntegrityVerifier.attachMac(key, data)
        val restored = DefaultMacIntegrityVerifier.verifyMac(key, secured)
        assertContentEquals(data, restored)
    }

    @Test
    fun `verifyMac should throw if data is tampered`() {
        val data = "Tamper me not".toByteArray()
        val secured = DefaultMacIntegrityVerifier.attachMac(key, data)
        secured[1] = (secured[1].toInt() xor 0xFF).toByte()
        assertFailsWith<SafeBoxDecryptionException> {
            DefaultMacIntegrityVerifier.verifyMac(key, secured)
        }
    }

    @Test
    fun `verifyMac should throw if input is too short`() {
        val invalid = ByteArray(8)
        assertFailsWith<SafeBoxDecryptionException> {
            DefaultMacIntegrityVerifier.verifyMac(key, invalid)
        }
    }

    private fun generateAesKey(): SecretKey = KeyGenerator.getInstance("AES")
        .apply { init(256) }
        .generateKey()
}
