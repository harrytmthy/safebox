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

    /**
     * Reopens the backing file in a fresh store and hands its persisted entries to [assertions].
     *
     * Reclamation bugs only show up here: [SafeBoxBlobStore.loadPersistedEntries] on the live
     * store reads its own mapped buffers, so it cannot tell whether the truncated file on disk
     * is still well-formed.
     */
    private suspend fun assertReopenedStore(assertions: (Map<Bytes, ByteArray>) -> Unit) {
        val reopened = SafeBoxBlobStore.create(context, fileName)
        try {
            assertions(reopened.loadPersistedEntries())
        } finally {
            reopened.closeWhenIdle()
        }
    }

    @Test
    fun loadAll_shouldReturnWrittenEntries() = runTest {
        val firstKey = "alpha".toByteArray().toBytes()
        val firstValue = "123".toByteArray()
        val secondKey = "beta".toByteArray().toBytes()
        val secondValue = "456".toByteArray()
        blobStore.write(firstKey, firstValue, false)
        blobStore.write(secondKey, secondValue, false)

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
        blobStore.write(firstKey, firstValue, false)
        blobStore.write(secondKey, secondValue, false)
        blobStore.write(thirdKey, thirdValue, false)

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
        blobStore.write(firstKey, firstValue, false)
        blobStore.write(secondKey, secondValue, false)
        blobStore.write(thirdKey, thirdValue, false)

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
        blobStore.write(firstKey, firstValue, false)
        blobStore.write(secondKey, secondValue, false)
        blobStore.write(thirdKey, thirdValue, false)

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
        blobStore.write(firstKey, firstValue, false)
        blobStore.write(secondKey, secondValue, false)

        blobStore.delete(firstKey)
        val thirdKey = "pete".toByteArray().toBytes()
        val thirdValue = "789".toByteArray()
        blobStore.write(thirdKey, thirdValue, false)

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

        blobStore.write(key, firstValue, false)
        blobStore.write(key, secondValue, false)

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

        blobStore.write(key, firstValue, false)
        blobStore.write(key, secondValue, false)

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

        blobStore.write(key, firstValue, false)
        blobStore.write(key, secondValue, false)

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

        blobStore.write(keyA, valueA, false)
        blobStore.write(keyB, valueB, false)

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
                blobStore.write(keys[it], value, false)
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
                blobStore.write(keys[it], value, false)
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
        blobStore.write(key, small, false)

        // Fill page 0 so only 8 bytes remain
        val filler = ByteArray(cap - (header + "k".length + small.size) - (header + "f".length) - 8)
        blobStore.write(fillerKey, filler, false)

        val larger = ByteArray(100)
        blobStore.write(key, larger, false) // should not fit page 0

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

        blobStore.write(keyA, valueA, false)
        blobStore.write(keyB, valueB, false) // rolls to page 1

        blobStore.deleteAll(true)

        val bin = File(context.noBackupFilesDir, "$fileName.bin")
        assertEquals(BUFFER_CAPACITY, bin.length())
        assertTrue(blobStore.entryMetas.isEmpty())
    }

    @Test
    fun deleteFromLastPage_shouldReclaimEmptyPageAfterFlush() = runTest {
        val keyA = "a".toByteArray().toBytes()
        val keyB = "b".toByteArray().toBytes()
        val valueA = ByteArray(BUFFER_CAPACITY.toInt() - (HEADER_SIZE + "a".length))
        val valueB = byteArrayOf(0x01)
        val regrownValueB = byteArrayOf(0x02)

        blobStore.write(keyA, valueA, false)
        blobStore.write(keyB, valueB, false)
        blobStore.flushDirtyPages()

        val bin = File(context.noBackupFilesDir, "$fileName.bin")
        assertEquals(BUFFER_CAPACITY * 2, bin.length())

        blobStore.delete(keyB, true)
        blobStore.flushDirtyPages()

        assertEquals(BUFFER_CAPACITY, bin.length())
        assertContentEquals(valueA, blobStore.loadPersistedEntries()[keyA])
        assertReopenedStore { entries ->
            assertEquals(1, entries.size)
            assertContentEquals(valueA, entries[keyA])
        }

        blobStore.write(keyB, regrownValueB, false)
        blobStore.flushDirtyPages()

        assertEquals(BUFFER_CAPACITY * 2, bin.length())
        assertEquals(1, blobStore.entryMetas.getValue(keyB).page)
        assertContentEquals(regrownValueB, blobStore.loadPersistedEntries()[keyB])
        // Page 1 has now been mapped, dropped, truncated away and mapped again at the same
        // offset while the first mapping may still be awaiting GC. Read it back from a fresh
        // store to confirm the regrown page persists its own content and not the old bytes.
        assertReopenedStore { entries ->
            assertEquals(2, entries.size)
            assertContentEquals(valueA, entries[keyA])
            assertContentEquals(regrownValueB, entries[keyB])
        }
    }

    @Test
    fun overwriteFromLastPage_shouldReclaimEmptyPageAfterFlush() = runTest {
        val fillerKey = "f".toByteArray().toBytes()
        val movedKey = "m".toByteArray().toBytes()
        val filler = ByteArray(BUFFER_CAPACITY.toInt() - (HEADER_SIZE + "f".length))
        val originalValue = ByteArray(64)
        val replacementValue = byteArrayOf(0x01)

        blobStore.write(fillerKey, filler, false)
        blobStore.write(movedKey, originalValue, false)
        blobStore.flushDirtyPages()

        blobStore.delete(fillerKey)
        blobStore.flushDirtyPages()

        val bin = File(context.noBackupFilesDir, "$fileName.bin")
        assertEquals(BUFFER_CAPACITY * 2, bin.length())

        blobStore.write(movedKey, replacementValue, true)
        blobStore.flushDirtyPages()

        assertEquals(BUFFER_CAPACITY, bin.length())
        assertEquals(0, blobStore.entryMetas.getValue(movedKey).page)
        assertContentEquals(replacementValue, blobStore.loadPersistedEntries()[movedKey])
        assertReopenedStore { entries ->
            assertEquals(1, entries.size)
            assertContentEquals(replacementValue, entries[movedKey])
        }
    }

    @Test
    fun deleteFromConsecutiveLastPages_shouldReclaimAllEmptyPagesAfterFlush() = runTest {
        val keys = List(3) { it.toString().toByteArray().toBytes() }
        keys.forEachIndexed { index, key ->
            val value = ByteArray(BUFFER_CAPACITY.toInt() - (HEADER_SIZE + index.toString().length))
            blobStore.write(key, value, false)
        }
        blobStore.flushDirtyPages()

        val bin = File(context.noBackupFilesDir, "$fileName.bin")
        assertEquals(BUFFER_CAPACITY * 3, bin.length())

        blobStore.delete(keys[1], keys[2])
        blobStore.flushDirtyPages()

        assertEquals(BUFFER_CAPACITY, bin.length())
        val survivor = ByteArray(BUFFER_CAPACITY.toInt() - (HEADER_SIZE + "0".length))
        assertContentEquals(survivor, blobStore.loadPersistedEntries()[keys[0]])
        assertFalse(blobStore.contains(keys[1]))
        assertFalse(blobStore.contains(keys[2]))
        assertReopenedStore { entries ->
            assertEquals(1, entries.size)
            assertContentEquals(survivor, entries[keys[0]])
        }
    }

    /**
     * Files written before tail reclamation existed can already carry an empty trailing page.
     * Opening one must reclaim it even though nothing in this session ever emptied a page, so
     * the reclaim scan cannot be gated on state accumulated since the store was constructed.
     */
    @Test
    fun flush_onFileWithPreexistingEmptyTailPage_shouldReclaim() = runTest {
        val keyA = "a".toByteArray().toBytes()
        val keyB = "b".toByteArray().toBytes()
        val valueA = ByteArray(BUFFER_CAPACITY.toInt() - (HEADER_SIZE + "a".length))

        blobStore.write(keyA, valueA, false)
        blobStore.write(keyB, byteArrayOf(0x01), false)
        blobStore.flushDirtyPages()
        blobStore.delete(keyB, true) // forceNow empties page 1 without leaving a dirty bit
        blobStore.closeWhenIdle() // closed before flushing, so the file keeps its second page

        val bin = File(context.noBackupFilesDir, "$fileName.bin")
        assertEquals(BUFFER_CAPACITY * 2, bin.length())

        val reopened = SafeBoxBlobStore.create(context, fileName)
        try {
            assertContentEquals(valueA, reopened.loadPersistedEntries()[keyA])
            reopened.flushDirtyPages()

            assertEquals(BUFFER_CAPACITY, bin.length())
        } finally {
            reopened.closeWhenIdle()
        }
        assertReopenedStore { entries ->
            assertEquals(1, entries.size)
            assertContentEquals(valueA, entries[keyA])
        }
    }

    @Test
    fun flush_withoutPendingChanges_shouldKeepFileIntact() = runTest {
        val key = "a".toByteArray().toBytes()
        val value = ByteArray(64)
        blobStore.write(key, value, false)
        blobStore.flushDirtyPages()

        val bin = File(context.noBackupFilesDir, "$fileName.bin")
        val lengthAfterWrite = bin.length()

        blobStore.flushDirtyPages()
        blobStore.flushDirtyPages()

        assertEquals(lengthAfterWrite, bin.length())
        assertContentEquals(value, blobStore.loadPersistedEntries()[key])
    }

    @Test
    fun deleteFromPage0_thenWrite_shouldReuseEarlierPage() = runTest {
        val keyA = "a".toByteArray().toBytes()
        val keyB = "b".toByteArray().toBytes()
        val keyC = "c".toByteArray().toBytes()

        val valueA = ByteArray(BUFFER_CAPACITY.toInt() - (HEADER_SIZE + "a".length))
        val valueB = ByteArray(HEADER_SIZE + "b".length)

        blobStore.write(keyA, valueA, false) // fills page 0 to leave 8 bytes
        blobStore.write(keyB, valueB, false) // goes to page 1

        blobStore.delete(keyA) // compact page 0, tail moves back

        val valueC = ByteArray(64)
        blobStore.write(keyC, valueC, false) // should reuse page 0 tail

        val result = blobStore.loadPersistedEntries()
        assertContentEquals(valueB, result[keyB])
        assertContentEquals(valueC, result[keyC])

        val metaB = blobStore.entryMetas.getValue(keyB)
        val metaC = blobStore.entryMetas.getValue(keyC)
        assertEquals(1, metaB.page)
        assertEquals(0, metaC.page)
    }

    @Test
    fun load_withCorruptedTail_shouldZeroFillAndKeepValidEntries() = runTest {
        val cap = BUFFER_CAPACITY.toInt()
        val remain = HEADER_SIZE - 2 // leave < HEADER_SIZE bytes to trigger corruption path
        val keyA = "a".toByteArray().toBytes()
        val valueA = ByteArray(cap - (HEADER_SIZE + "a".length) - remain)
        blobStore.write(keyA, valueA, false)

        // Manually corrupt the tail bytes (non-zero) so loader must repair (zero-fill) them
        val bin = File(context.noBackupFilesDir, "$fileName.bin")
        java.io.RandomAccessFile(bin, "rw").use { raf ->
            raf.seek((cap - remain).toLong())
            raf.write(byteArrayOf(0x55, 0x55, 0x55, 0x55))
        }
        val result = blobStore.loadPersistedEntries()
        assertEquals(1, result.size)
        assertContentEquals(valueA, result[keyA])

        // Verify the corrupted region was zero-filled.
        java.io.RandomAccessFile(bin, "r").use { raf ->
            raf.seek((cap - remain).toLong())
            val tail = ByteArray(remain)
            raf.readFully(tail)
            assertTrue(tail.all { it == 0.toByte() })
        }

        // Next write can't fit in the repaired tail (< HEADER_SIZE), so it should roll to page 1
        val keyB = "b".toByteArray().toBytes()
        val valueB = byteArrayOf(1)
        blobStore.write(keyB, valueB, false)
        val metaA = blobStore.entryMetas.getValue(keyA)
        val metaB = blobStore.entryMetas.getValue(keyB)
        assertEquals(0, metaA.page)
        assertEquals(1, metaB.page)
        assertEquals(0, metaB.offset)
    }
}
