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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SafeBoxBlobStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val fileName: String = "safebox_blob_test"

    private val blobStore = SafeBoxBlobStore.create(context, fileName, UnconfinedTestDispatcher())

    @After
    fun teardown() {
        blobStore.close()
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

        val result = blobStore.getAll()

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

        val result = blobStore.getAll()
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

        val result = blobStore.getAll()
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

        val result = blobStore.getAll()
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

        val result = blobStore.getAll()
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

        val result = blobStore.getAll()
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

        val result = blobStore.getAll()
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

        val result = blobStore.getAll()
        assertEquals(1, result.size)
        assertContentEquals(secondValue, result[key])
        assertTrue(blobStore.entryMetas.containsKey(key))
    }

    @Test
    fun getFileName_shouldReturnFileName() {
        assertEquals(fileName, blobStore.getFileName())
    }
}
