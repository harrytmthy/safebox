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

package com.harrytmthy.safebox

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harrytmthy.safebox.SafeBox.Companion.DEFAULT_KEY_ALIAS
import com.harrytmthy.safebox.state.SafeBoxStateListener
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SafeBoxTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val fileName = "safebox_test"

    private lateinit var safeBox: SafeBox

    @After
    fun tearDown() {
        SafeBox.getOrNull(fileName)
            ?.edit()
            ?.clear()
            ?.commit()

        File(context.noBackupFilesDir, "$fileName.bin").delete()
        File(context.noBackupFilesDir, "$DEFAULT_KEY_ALIAS.bin").delete()
    }

    @Test
    fun create_then_get_shouldReturnSameInstance() {
        safeBox = createSafeBox()

        val instance = SafeBox.get(fileName)

        assertTrue(safeBox === instance)
    }

    @Test
    fun create_withConcurrentCalls_shouldReturnSameInstance() = runTest {
        val createdInstance = AtomicReference<SafeBox>()
        repeat(10) {
            launch(Dispatchers.IO) {
                val newInstance = createSafeBox()
                val previousInstance = createdInstance.getAndSet(newInstance) ?: return@launch
                assertTrue(newInstance === previousInstance)
            }
        }
    }

    @Test
    fun create_withDifferentFileName_shouldReturnDifferentInstances() {
        safeBox = createSafeBox()

        val anotherSafeBox = createSafeBox(fileName = "test_safebox.bin")

        assertTrue(safeBox !== anotherSafeBox)
        assertTrue(safeBox === createSafeBox())
        File(context.noBackupFilesDir, "test_safebox.bin").delete()
    }

    @Test
    fun getString_shouldReturnCorrectValue() {
        safeBox = createSafeBox()
        safeBox.edit()
            .putString("SafeBox", "Secured")
            .apply()

        val value = safeBox.getString("SafeBox", null)

        assertEquals("Secured", value)
    }

    @Test
    fun getString_withRealIoDispatcher_shouldReturnCorrectValue() {
        safeBox = createSafeBox(ioDispatcher = Dispatchers.IO)
        safeBox.edit()
            .putString("SafeBox", "Secured")
            .apply()

        val value = safeBox.getString("SafeBox", null)

        assertEquals("Secured", value)
    }

    @Test
    fun getString_afterRemove_shouldReturnDefaultValue() {
        safeBox = createSafeBox()
        safeBox.edit()
            .putString("SafeBox", "Secured")
            .apply()

        safeBox.edit()
            .remove("SafeBox")
            .commit()

        assertNull(safeBox.getString("SafeBox", null))
    }

    @Test
    fun getString_afterClear_shouldReturnDefaultValue() {
        safeBox = createSafeBox()
        safeBox.edit()
            .putString("firstKey", "firstValue")
            .putInt("secondKey", 42)
            .commit()

        safeBox.edit()
            .clear()
            .commit()

        assertEquals(null, safeBox.getString("firstKey", null))
        assertEquals(0, safeBox.getInt("secondKey", 0))
    }

    @Test
    fun commit_shouldWaitForApplyCompletion() {
        safeBox = createSafeBox()
        safeBox.edit().apply {
            repeat(100) {
                putInt(it.toString(), it)
            }
        }.apply()

        safeBox.edit()
            .clear()
            .commit()

        assertTrue(safeBox.all.isEmpty())
    }

    @Test
    fun listener_shouldBeCalledOnPutRemoveAndClear() {
        safeBox = createSafeBox()
        val changedKeys = ArrayList<String?>()
        val changedValues = ArrayList<Any?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            changedKeys.add(key)
            changedValues += safeBox.all[key]
        }
        safeBox.registerOnSharedPreferenceChangeListener(listener)

        safeBox.edit()
            .putFloat("key0", 0.2f)
            .commit()
        safeBox.edit()
            .putLong("key1", 1L)
            .putBoolean("key2", true)
            .remove("key1")
            .clear()
            .putStringSet("key3", setOf("SafeBox"))
            .commit()

        safeBox.unregisterOnSharedPreferenceChangeListener(listener)

        val expectedKeyChanges = listOf(
            "key0", // put Float
            "key0", // cleared Float
            "key1", // put + removed Long
            "key2", // put Boolean
            "key3", // put StringSet
        )
        val expectedValueChanges = listOf(
            0.2f, // put Float
            null, // cleared Float
            null, // put + removed Long
            true, // put Boolean
            setOf("SafeBox"), // put StringSet
        )
        assertContentEquals(expectedKeyChanges, changedKeys)
        assertContentEquals(expectedValueChanges, changedValues)
    }

    @Test
    fun apply_then_commit_shouldHaveCorrectOrder() = runTest {
        safeBox = createSafeBox(ioDispatcher = Dispatchers.IO)

        withTimeout(10.seconds) {
            safeBox.edit().putInt("0", 0).apply()
            safeBox.edit().putInt("1", 1).apply()
            assertTrue(safeBox.edit().clear().commit())
            safeBox.edit().putInt("2", 2).apply()
            safeBox.edit().putInt("3", 3).apply()
            assertTrue(safeBox.edit().clear().commit())
            safeBox.edit().putInt("4", 4).apply()
        }

        assertEquals(4, safeBox.getInt("4", -1))
        assertEquals(-1, safeBox.getInt("3", -1))
        assertEquals(-1, safeBox.getInt("2", -1))
        assertEquals(-1, safeBox.getInt("1", -1))
        assertEquals(-1, safeBox.getInt("0", -1))
    }

    @Test
    fun apply_then_commit_whenRepeatedManyTimes_shouldReturnCorrectValues() {
        safeBox = createSafeBox(ioDispatcher = Dispatchers.IO)

        safeBox.edit().apply {
            repeat(100) {
                putInt(it.toString(), it)
                if (it % 2 == 0) {
                    apply()
                } else {
                    commit()
                }
            }
        }

        repeat(100) {
            assertEquals(it, safeBox.getInt(it.toString(), -1))
        }
    }

    private fun createSafeBox(
        fileName: String = this.fileName,
        ioDispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
        stateListener: SafeBoxStateListener? = null,
    ): SafeBox =
        SafeBox.create(
            context = context,
            fileName = fileName,
            ioDispatcher = ioDispatcher,
            stateListener = stateListener,
        )
}
