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
import com.harrytmthy.safebox.SafeBox.Companion.DEFAULT_VALUE_KEYSTORE_ALIAS
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SafeBoxTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val fileName = "safebox_test"

    private val safeBox = SafeBox.create(
        context = context,
        fileName = fileName,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    @After
    fun tearDown() {
        safeBox.edit()
            .clear()
            .commit()

        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(DEFAULT_VALUE_KEYSTORE_ALIAS)
        }

        File(context.noBackupFilesDir, "$fileName.bin").delete()
        File(context.noBackupFilesDir, "$DEFAULT_KEY_ALIAS.bin").delete()
    }

    @Test
    fun getString_shouldReturnCorrectValue() {
        safeBox.edit()
            .putString("SafeBox", "Secured")
            .apply()

        val value = safeBox.getString("SafeBox", null)

        assertEquals("Secured", value)
    }

    @Test
    fun getString_afterRemove_shouldReturnDefaultValue() {
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
}
