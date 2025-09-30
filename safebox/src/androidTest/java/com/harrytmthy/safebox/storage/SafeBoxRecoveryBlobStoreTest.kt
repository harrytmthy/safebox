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
import com.harrytmthy.safebox.storage.SafeBoxRecoveryBlobStore.Companion.BUFFER_CAPACITY
import com.harrytmthy.safebox.storage.SafeBoxRecoveryBlobStore.Companion.FILE_NAME
import com.harrytmthy.safebox.storage.SafeBoxRecoveryBlobStore.Companion.HEADER_SIZE
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SafeBoxRecoveryBlobStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val recovery = SafeBoxRecoveryBlobStore.getOrCreate(context)

    private val firstFile = "firstFile".toBytes()

    private val secondFile = "secondFile".toBytes()

    @AfterTest
    fun cleanup() {
        File(context.noBackupFilesDir, "$FILE_NAME.bin").delete()
    }

    @Test
    fun load_shouldReturnOnlyEntriesForRequestedFile() = runTest {
        val firstKeyForFirstFile = "firstKeyForFirstFile".toBytes()
        val secondKeyForFirstFile = "secondKeyForFirstFile".toBytes()
        val firstKeyForSecondFile = "firstKeyForSecondFile".toBytes()

        val firstValueForFirstFile = "FIRST_FILE_VALUE_1".toByteArray()
        val secondValueForFirstFile = "FIRST_FILE_VALUE_2".toByteArray()
        val firstValueForSecondFile = "SECOND_FILE_VALUE_1".toByteArray()

        recovery.write(firstFile, firstKeyForFirstFile, firstValueForFirstFile)
        recovery.write(secondFile, firstKeyForSecondFile, firstValueForSecondFile)
        recovery.write(firstFile, secondKeyForFirstFile, secondValueForFirstFile)

        val firstFileEntries = recovery.loadPersistedEntries(firstFile)
        val secondFileEntries = recovery.loadPersistedEntries(secondFile)

        assertEquals(2, firstFileEntries.size)
        assertContentEquals(firstValueForFirstFile, firstFileEntries[firstKeyForFirstFile])
        assertContentEquals(secondValueForFirstFile, firstFileEntries[secondKeyForFirstFile])

        assertEquals(1, secondFileEntries.size)
        assertContentEquals(firstValueForSecondFile, secondFileEntries[firstKeyForSecondFile])

        recovery.delete(firstFile)
        recovery.delete(secondFile)
    }

    @Test
    fun write_sameKey_shouldReplaceAndLoadLatest() = runTest {
        val key = "key".toBytes()
        val smallValue = "s".toByteArray()
        val largerValue = ByteArray(128) { 7 }

        recovery.write(firstFile, key, smallValue)
        recovery.write(firstFile, key, largerValue)

        val entries = recovery.loadPersistedEntries(firstFile)
        assertEquals(1, entries.size)
        assertContentEquals(largerValue, entries[key])

        recovery.delete(firstFile)
    }

    @Test
    fun delete_file_shouldCompactAndRemoveOnlyThatFile() = runTest {
        val keyInFirstFile = "firstFileKey".toBytes()
        val keyInSecondFile = "secondFileKey".toBytes()
        val valueInFirstFile = byteArrayOf(1, 2, 3)
        val valueInSecondFile = byteArrayOf(9)

        recovery.write(firstFile, keyInFirstFile, valueInFirstFile)
        recovery.write(secondFile, keyInSecondFile, valueInSecondFile)

        recovery.delete(firstFile)

        val firstFileEntries = recovery.loadPersistedEntries(firstFile)
        val secondFileEntries = recovery.loadPersistedEntries(secondFile)

        assertTrue(firstFileEntries.isEmpty())
        assertEquals(1, secondFileEntries.size)
        assertContentEquals(valueInSecondFile, secondFileEntries[keyInSecondFile])

        recovery.delete(secondFile)
    }

    @Test
    fun write_tooLargeEntry_shouldThrow() = runTest {
        val key = "key".toBytes()
        val maxValueSize = BUFFER_CAPACITY - (HEADER_SIZE + firstFile.size + key.size)
        val tooLargeValue = ByteArray(maxValueSize + 1)

        assertFailsWith<IllegalStateException> {
            recovery.write(firstFile, key, tooLargeValue)
        }
    }
}
