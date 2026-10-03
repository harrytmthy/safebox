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
import kotlin.test.assertEquals
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
            index.load("key-$it", "key-$it".toByteArray().toBytes(), byteArrayOf(it.toByte()))
        }
        val executor = Executors.newFixedThreadPool(8)
        try {
            val reads = List(8) {
                Callable {
                    repeat(1000) { iteration ->
                        val entry = assertNotNull(index["key-${iteration % 100}"])
                        assertContentEquals(
                            expected = byteArrayOf((iteration % 100).toByte()),
                            actual = entry.encryptedValue,
                        )
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
        index.load(index.decodeKey(encryptedKey), encryptedKey, byteArrayOf(1))
        val old = assertNotNull(index["key"])
        index.put(index.lookup("key"), encryptedKey, byteArrayOf(2))

        assertFalse(index.remove(old))
        assertContentEquals(byteArrayOf(1), old.encryptedValue)
        val current = assertNotNull(index["key"])
        assertContentEquals(byteArrayOf(2), current.encryptedValue)
        assertTrue(index.remove(current))
        assertNull(index["key"])
    }

    @Test
    fun lookup_afterReplacement_shouldReadCurrentStateAndRetainItsOriginalRecordIdentity() {
        val index = EntryIndex(IdentityCipherProvider())
        val oldKey = "old-key".toByteArray().toBytes()
        val currentKey = "current-key".toByteArray().toBytes()
        index.load("key", oldKey, byteArrayOf(1))
        val lookup = index.lookup("key")

        index.put(lookup, currentKey, byteArrayOf(2))

        assertContentEquals(byteArrayOf(2), assertNotNull(index[lookup]).encryptedValue)
        assertContentEquals(byteArrayOf(1), assertNotNull(lookup.entry).encryptedValue)
        assertEquals(oldKey, index.resolveEncryptedKey("key", lookup))
    }

    @Test
    fun lookup_afterRemoval_shouldReuseStoredRecordIdentityWithoutEncryptingTheKey() {
        val index = EntryIndex(IdentityCipherProvider())
        val encryptedKey = "stored-key".toByteArray().toBytes()
        index.load("key", encryptedKey, byteArrayOf(1))
        val lookup = index.lookup("key")

        index.remove(lookup)

        assertNull(index[lookup])
        assertEquals(encryptedKey, index.resolveEncryptedKey("key", lookup))
    }

    private class IdentityCipherProvider : CipherProvider {

        override fun encrypt(plaintext: ByteArray): ByteArray = error("Unexpected key encryption")

        override fun decrypt(ciphertext: ByteArray): ByteArray = ciphertext
    }
}
