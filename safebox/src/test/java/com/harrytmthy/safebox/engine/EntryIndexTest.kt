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

package com.harrytmthy.safebox.engine

import com.harrytmthy.safebox.cryptography.CipherProvider
import com.harrytmthy.safebox.extensions.toBytes
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntryIndexTest {

    @Test
    fun concurrentReads_shouldResolveEveryKeyWithTheSharedMac() {
        val cipher = IdentityCipherProvider()
        val index = EntryIndex(cipher)
        repeat(100) {
            index.load("key-$it".toByteArray().toBytes(), byteArrayOf(it.toByte()))
        }
        val executor = Executors.newFixedThreadPool(8)
        try {
            val reads = List(8) {
                Callable {
                    repeat(1000) { iteration ->
                        val entry = assertNotNull(index["key-${iteration % 100}"])
                        assertContentEquals(byteArrayOf((iteration % 100).toByte()), entry.encryptedValue)
                        assertNull(index["missing"])
                    }
                }
            }
            executor.invokeAll(reads, 10, TimeUnit.SECONDS).forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun remove_withAnOldSnapshot_shouldNotRemoveItsReplacement() {
        val index = EntryIndex(IdentityCipherProvider())
        val encryptedKey = "key".toByteArray().toBytes()
        index.load(encryptedKey, byteArrayOf(1))
        val old = assertNotNull(index["key"])
        index.put(index.lookup("key"), encryptedKey, byteArrayOf(2))

        assertFalse(index.remove(old))
        assertContentEquals(byteArrayOf(1), old.encryptedValue)
        val current = assertNotNull(index["key"])
        assertContentEquals(byteArrayOf(2), current.encryptedValue)
        assertTrue(index.remove(current))
        assertNull(index["key"])
    }

    private class IdentityCipherProvider : CipherProvider {

        override fun encrypt(plaintext: ByteArray): ByteArray = error("Unexpected key encryption")

        override fun decrypt(ciphertext: ByteArray): ByteArray = ciphertext
    }
}
