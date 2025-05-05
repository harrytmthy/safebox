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
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SafeBoxBlobStoreTest {

    private lateinit var file: File
    private lateinit var store: SafeBoxBlobStore

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File(context.filesDir, "safebox_blob_test.bin")
        if (file.exists()) file.delete()
        store = SafeBoxBlobStore(file)
    }

    @After
    fun teardown() {
        store.close()
        file.delete()
    }

    @Test
    fun loadAll_shouldReturnWrittenEntries() = runTest {
        store.loadAll()
        val firstKey = "alpha".toByteArray()
        val firstValue = "123".toByteArray()
        val secondKey = "beta".toByteArray()
        val secondValue = "456".toByteArray()
        store.write(firstKey, firstValue)
        store.write(secondKey, secondValue)

        val result = store.loadAll()

        assertEquals(2, result.size)
        assertContentEquals(firstValue, result[firstKey.toBytes()])
        assertContentEquals(secondValue, result[secondKey.toBytes()])
    }

    @Test
    fun delete_shouldRemoveFirstEntry() = runTest {
        store.loadAll()
        val firstKey = "alpha".toByteArray()
        val firstValue = "123".toByteArray()
        val secondKey = "beta".toByteArray()
        val secondValue = "456".toByteArray()
        val thirdKey = "pete".toByteArray()
        val thirdValue = "789".toByteArray()
        store.write(firstKey, firstValue)
        store.write(secondKey, secondValue)
        store.write(thirdKey, thirdValue)

        store.delete(firstKey)

        val result = store.loadAll()
        assertEquals(2, result.size)
        assertContentEquals(null, result[firstKey.toBytes()])
        assertContentEquals(secondValue, result[secondKey.toBytes()])
        assertContentEquals(thirdValue, result[thirdKey.toBytes()])
    }

    @Test
    fun delete_shouldRemoveSecondEntry() = runTest {
        store.loadAll()
        val firstKey = "alpha".toByteArray()
        val firstValue = "123".toByteArray()
        val secondKey = "beta".toByteArray()
        val secondValue = "456".toByteArray()
        val thirdKey = "pete".toByteArray()
        val thirdValue = "789".toByteArray()
        store.write(firstKey, firstValue)
        store.write(secondKey, secondValue)
        store.write(thirdKey, thirdValue)

        store.delete(secondKey)

        val result = store.loadAll()
        assertEquals(2, result.size)
        assertContentEquals(firstValue, result[firstKey.toBytes()])
        assertContentEquals(null, result[secondKey.toBytes()])
        assertContentEquals(thirdValue, result[thirdKey.toBytes()])
    }

    @Test
    fun delete_shouldRemoveLastEntry() = runTest {
        store.loadAll()
        val firstKey = "alpha".toByteArray()
        val firstValue = "123".toByteArray()
        val secondKey = "beta".toByteArray()
        val secondValue = "456".toByteArray()
        val thirdKey = "pete".toByteArray()
        val thirdValue = "789".toByteArray()
        store.write(firstKey, firstValue)
        store.write(secondKey, secondValue)
        store.write(thirdKey, thirdValue)

        store.delete(thirdKey)

        val result = store.loadAll()
        assertEquals(2, result.size)
        assertContentEquals(firstValue, result[firstKey.toBytes()])
        assertContentEquals(secondValue, result[secondKey.toBytes()])
        assertContentEquals(null, result[thirdKey.toBytes()])
    }

    @Test
    fun deleteAndWrite_shouldReflectCorrectMetaState() = runTest {
        store.loadAll()
        val firstKey = "alpha".toByteArray()
        val firstValue = "123".toByteArray()
        val secondKey = "beta".toByteArray()
        val secondValue = "456".toByteArray()
        store.write(firstKey, firstValue)
        store.write(secondKey, secondValue)

        store.delete(firstKey)
        val thirdKey = "pete".toByteArray()
        val thirdValue = "789".toByteArray()
        store.write(thirdKey, thirdValue)

        val result = store.loadAll()
        assertEquals(2, result.size)
        assertContentEquals(null, result[firstKey.toBytes()])
        assertContentEquals(secondValue, result[secondKey.toBytes()])
        assertContentEquals(thirdValue, result[thirdKey.toBytes()])
        assertFalse(store.entryMetaByKey.containsKey(firstKey.toBytes()))
        assertTrue(store.entryMetaByKey.containsKey(secondKey.toBytes()))
        assertTrue(store.entryMetaByKey.containsKey(thirdKey.toBytes()))
    }

    @Test
    fun write_withSameNewSize_shouldOverwriteExitingValue() = runTest {
        store.loadAll()
        val key = "alpha".toByteArray()
        val firstValue = "123".toByteArray()
        val secondValue = "456".toByteArray()

        store.write(key, firstValue)
        store.write(key, secondValue)

        val result = store.loadAll()
        assertEquals(1, result.size)
        assertContentEquals(secondValue, result[key.toBytes()])
        assertTrue(store.entryMetaByKey.containsKey(key.toBytes()))
    }

    @Test
    fun write_withSmallerNewSize_shouldOverwriteExistingValue() = runTest {
        store.loadAll()
        val key = "alpha".toByteArray()
        val firstValue = "12345".toByteArray()
        val secondValue = "1".toByteArray()

        store.write(key, firstValue)
        store.write(key, secondValue)

        val result = store.loadAll()
        assertEquals(1, result.size)
        assertContentEquals(secondValue, result[key.toBytes()])
        assertTrue(store.entryMetaByKey.containsKey(key.toBytes()))
    }

    @Test
    fun write_withLargerNewSize_shouldOverwriteExistingValue() = runTest {
        store.loadAll()
        val key = "alpha".toByteArray()
        val firstValue = "1".toByteArray()
        val secondValue = "12345".toByteArray()

        store.write(key, firstValue)
        store.write(key, secondValue)

        val result = store.loadAll()
        assertEquals(1, result.size)
        assertContentEquals(secondValue, result[key.toBytes()])
        assertTrue(store.entryMetaByKey.containsKey(key.toBytes()))
    }
}
