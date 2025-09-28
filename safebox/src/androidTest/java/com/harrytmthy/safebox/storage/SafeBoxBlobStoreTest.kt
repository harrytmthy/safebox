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

package com.harrytmthy.safebox.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harrytmthy.safebox.extensions.toBytes
import com.harrytmthy.safebox.storage.SafeBoxBlobStore.Companion.BUFFER_CAPACITY
import com.harrytmthy.safebox.storage.SafeBoxBlobStore.Companion.HEADER_SIZE
import com.harrytmthy.safebox.storage.SafeBoxBlobStore.Companion.MAX_PAGE
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SafeBoxBlobStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val fileName: String = "safebox_blob_test"

    private val blobStore = SafeBoxBlobStore.create(context, fileName)

    @After
    fun teardown() {
        File(context.noBackupFilesDir, "$fileName.bin").delete()
    }

    @Test
    fun loadAll_shouldReturnWrittenEntries() = runTest {
        val firstKey = "alpha".toByteArray().toBytes()
        val firstValue = "123".toByteArray()
        val secondKey = "beta".toByteArray().toBytes()
        val secondValue = "456".toByteArray()
        blobStore.write(firstKey, firstValue)
        blobStore.write(secondKey, secondValue)

        val result = blobStore.loadPersistedEntries()

        assertEquals(2, result.size)
        assertContentEquals(firstValue, result[firstKey])
        assertContentEquals(secondValue, result[secondKey])
    }

    @Test
    fun delete_shouldRemoveFirstEntry() = runTest {
        val firstKey = "alpha".toByteArray().toBytes()
        val firstValue = "123".toByteArray()
        val secondKey = "beta".toByteArray().toBytes()
        val secondValue = "456".toByteArray()
        val thirdKey = "pete".toByteArray().toBytes()
        val thirdValue = "789".toByteArray()
        blobStore.write(firstKey, firstValue)
        blobStore.write(secondKey, secondValue)
        blobStore.write(thirdKey, thirdValue)

        blobStore.delete(firstKey)

        val result = blobStore.loadPersistedEntries()
        assertEquals(2, result.size)
        assertFalse(result.any { it == firstKey })
        assertContentEquals(secondValue, result[secondKey])
        assertContentEquals(thirdValue, result[thirdKey])
        assertFalse(blobStore.entryMetas.containsKey(firstKey))
    }

    @Test
    fun delete_shouldRemoveSecondEntry() = runTest {
        val firstKey = "alpha".toByteArray().toBytes()
        val firstValue = "123".toByteArray()
        val secondKey = "beta".toByteArray().toBytes()
        val secondValue = "456".toByteArray()
        val thirdKey = "pete".toByteArray().toBytes()
        val thirdValue = "789".toByteArray()
        blobStore.write(firstKey, firstValue)
        blobStore.write(secondKey, secondValue)
        blobStore.write(thirdKey, thirdValue)

        blobStore.delete(secondKey)

        val result = blobStore.loadPersistedEntries()
        assertEquals(2, result.size)
        assertFalse(result.any { it == secondKey })
        assertContentEquals(firstValue, result[firstKey])
        assertContentEquals(thirdValue, result[thirdKey])
        assertFalse(blobStore.entryMetas.containsKey(secondKey))
    }

    @Test
    fun delete_shouldRemoveLastEntry() = runTest {
        val firstKey = "alpha".toByteArray().toBytes()
        val firstValue = "123".toByteArray()
        val secondKey = "beta".toByteArray().toBytes()
        val secondValue = "456".toByteArray()
        val thirdKey = "pete".toByteArray().toBytes()
        val thirdValue = "789".toByteArray()
        blobStore.write(firstKey, firstValue)
        blobStore.write(secondKey, secondValue)
        blobStore.write(thirdKey, thirdValue)

        blobStore.delete(thirdKey)

        val result = blobStore.loadPersistedEntries()
        assertEquals(2, result.size)
        assertFalse(result.any { it == thirdKey })
        assertContentEquals(firstValue, result[firstKey])
        assertContentEquals(secondValue, result[secondKey])
        assertFalse(blobStore.entryMetas.containsKey(thirdKey))
    }

    @Test
    fun deleteAndWrite_shouldReflectCorrectMetaState() = runTest {
        val firstKey = "alpha".toByteArray().toBytes()
        val firstValue = "123".toByteArray()
        val secondKey = "beta".toByteArray().toBytes()
        val secondValue = "456".toByteArray()
        blobStore.write(firstKey, firstValue)
        blobStore.write(secondKey, secondValue)

        blobStore.delete(firstKey)
        val thirdKey = "pete".toByteArray().toBytes()
        val thirdValue = "789".toByteArray()
        blobStore.write(thirdKey, thirdValue)

        val result = blobStore.loadPersistedEntries()
        assertEquals(2, result.size)
        assertFalse(result.any { it == firstKey })
        assertContentEquals(secondValue, result[secondKey])
        assertContentEquals(thirdValue, result[thirdKey])
        assertFalse(blobStore.entryMetas.containsKey(firstKey))
        assertTrue(blobStore.entryMetas.containsKey(secondKey))
        assertTrue(blobStore.entryMetas.containsKey(thirdKey))
    }

    @Test
    fun write_withSameNewSize_shouldOverwriteExitingValue() = runTest {
        val key = "alpha".toByteArray().toBytes()
        val firstValue = "123".toByteArray()
        val secondValue = "456".toByteArray()

        blobStore.write(key, firstValue)
        blobStore.write(key, secondValue)

        val result = blobStore.loadPersistedEntries()
        assertEquals(1, result.size)
        assertContentEquals(secondValue, result[key])
        assertTrue(blobStore.entryMetas.containsKey(key))
    }

    @Test
    fun write_withSmallerNewSize_shouldOverwriteExistingValue() = runTest {
        val key = "alpha".toByteArray().toBytes()
        val firstValue = "12345".toByteArray()
        val secondValue = "1".toByteArray()

        blobStore.write(key, firstValue)
        blobStore.write(key, secondValue)

        val result = blobStore.loadPersistedEntries()
        assertEquals(1, result.size)
        assertContentEquals(secondValue, result[key])
        assertTrue(blobStore.entryMetas.containsKey(key))
    }

    @Test
    fun write_withLargerNewSize_shouldOverwriteExistingValue() = runTest {
        val key = "alpha".toByteArray().toBytes()
        val firstValue = "1".toByteArray()
        val secondValue = "12345".toByteArray()

        blobStore.write(key, firstValue)
        blobStore.write(key, secondValue)

        val result = blobStore.loadPersistedEntries()
        assertEquals(1, result.size)
        assertContentEquals(secondValue, result[key])
        assertTrue(blobStore.entryMetas.containsKey(key))
    }

    @Test
    fun getFileName_shouldReturnFileName() {
        assertEquals(fileName, blobStore.getFileName())
    }

    @Test
    fun write_crossesPageBoundary_shouldRolloverToNextPage() = runTest {
        val keyA = "a".toByteArray().toBytes()
        val keyB = "b".toByteArray().toBytes()

        val valueA = ByteArray(BUFFER_CAPACITY.toInt() - (HEADER_SIZE + "a".length))
        val valueB = ByteArray(HEADER_SIZE + "b".length)

        blobStore.write(keyA, valueA)
        blobStore.write(keyB, valueB)

        val result = blobStore.loadPersistedEntries()

        assertContentEquals(valueA, result[keyA])
        assertContentEquals(valueB, result[keyB])

        val metaA = blobStore.entryMetas.getValue(keyA)
        val metaB = blobStore.entryMetas.getValue(keyB)
        assertEquals(0, metaA.page)
        assertEquals(1, metaB.page)
        assertEquals(0, metaB.offset)
    }

    @Test
    fun write_whenReachingMaxPage_shouldNotThrow() = runTest {
        val size = MAX_PAGE
        val keys = buildList {
            repeat(size) {
                add(it.toString().toByteArray().toBytes())
            }
        }

        var exception: IllegalStateException? = null
        try {
            repeat(size) {
                val value = ByteArray(BUFFER_CAPACITY.toInt() - (HEADER_SIZE + it.toString().length))
                blobStore.write(keys[it], value)
            }
        } catch (e: IllegalStateException) {
            exception = e
        }

        val result = blobStore.loadPersistedEntries()
        assertEquals(size, result.size)
        assertNull(exception)
    }

    @Test
    fun write_whenExceedingMaxPage_shouldThrow() = runTest {
        val size = MAX_PAGE + 1
        val keys = buildList {
            repeat(size) {
                add(it.toString().toByteArray().toBytes())
            }
        }

        var exception: IllegalStateException? = null
        try {
            repeat(size) {
                val value = ByteArray(BUFFER_CAPACITY.toInt() - (HEADER_SIZE + it.toString().length))
                blobStore.write(keys[it], value)
            }
        } catch (e: IllegalStateException) {
            exception = e
        }

        assertNotNull(exception)
    }

    @Test
    fun overwrite_whenNoFitInSamePage_shouldAllocateNewPageAndMoveEntry() = runTest {
        val cap = BUFFER_CAPACITY.toInt()
        val header = HEADER_SIZE

        val key = "k".toByteArray().toBytes()
        val fillerKey = "f".toByteArray().toBytes()

        val small = ByteArray(10)
        blobStore.write(key, small)

        // Fill page 0 so only 8 bytes remain
        val filler = ByteArray(cap - (header + "k".length + small.size) - (header + "f".length) - 8)
        blobStore.write(fillerKey, filler)

        val larger = ByteArray(100)
        blobStore.write(key, larger) // should not fit page 0

        val result = blobStore.loadPersistedEntries()
        assertContentEquals(larger, result[key])
        assertContentEquals(filler, result[fillerKey])

        val metaKey = blobStore.entryMetas.getValue(key)
        val metaFiller = blobStore.entryMetas.getValue(fillerKey)
        assertEquals(1, metaKey.page)
        assertEquals(0, metaKey.offset)
        assertEquals(0, metaFiller.page)
    }

    @Test
    fun deleteAll_shouldShrinkFileBackToSinglePage() = runTest {
        val keyA = "a".toByteArray().toBytes()
        val keyB = "b".toByteArray().toBytes()

        val valueA = ByteArray(BUFFER_CAPACITY.toInt() - (HEADER_SIZE + "a".length) - 8)
        val valueB = byteArrayOf(0x01)

        blobStore.write(keyA, valueA)
        blobStore.write(keyB, valueB) // rolls to page 1

        blobStore.deleteAll()

        val bin = File(context.noBackupFilesDir, "$fileName.bin")
        assertEquals(BUFFER_CAPACITY, bin.length())
        assertTrue(blobStore.entryMetas.isEmpty())
    }

    @Test
    fun deleteFromPage0_thenWrite_shouldReuseEarlierPage() = runTest {
        val keyA = "a".toByteArray().toBytes()
        val keyB = "b".toByteArray().toBytes()
        val keyC = "c".toByteArray().toBytes()

        val valueA = ByteArray(BUFFER_CAPACITY.toInt() - (HEADER_SIZE + "a".length))
        val valueB = ByteArray(HEADER_SIZE + "b".length)

        blobStore.write(keyA, valueA) // fills page 0 to leave 8 bytes
        blobStore.write(keyB, valueB) // goes to page 1

        blobStore.delete(keyA) // compact page 0, tail moves back

        val valueC = ByteArray(64)
        blobStore.write(keyC, valueC) // should reuse page 0 tail

        val result = blobStore.loadPersistedEntries()
        assertContentEquals(valueB, result[keyB])
        assertContentEquals(valueC, result[keyC])

        val metaB = blobStore.entryMetas.getValue(keyB)
        val metaC = blobStore.entryMetas.getValue(keyC)
        assertEquals(1, metaB.page)
        assertEquals(0, metaC.page)
    }
}
