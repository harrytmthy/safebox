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

package com.harrytmthy.safebox.migration

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harrytmthy.safebox.SafeBox
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.runner.RunWith
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SafeBoxMigrationHelperTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val fileEsp = "test_esp_prefs"
    private val fileSafeBox = "test_safebox_prefs"

    private val esp: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            fileEsp,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val safeBox = SafeBox.create(
        context,
        fileSafeBox,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    @AfterTest
    fun tearDown() {
        esp.edit()
            .clear()
            .commit()
        context.deleteSharedPreferences(fileEsp)
        context.deleteFile("$fileSafeBox.bin")
    }

    @Test
    fun migrate_fromEsp_toSafeBox_shouldCopyAllEntries() {
        esp.edit()
            .putString("key_str", "SafeBox")
            .putInt("key_int", 33)
            .putBoolean("key_bool", true)
            .putFloat("key_float", 3.14f)
            .putLong("key_long", 123456L)
            .putStringSet("key_set", setOf("a", "b"))
            .commit()

        SafeBoxMigrationHelper.migrate(esp, safeBox)

        assertEquals("SafeBox", safeBox.getString("key_str", null))
        assertEquals(33, safeBox.getInt("key_int", -1))
        assertEquals(true, safeBox.getBoolean("key_bool", false))
        assertEquals(3.14f, safeBox.getFloat("key_float", 0f))
        assertEquals(123456L, safeBox.getLong("key_long", 0L))
        assertEquals(setOf("a", "b"), safeBox.getStringSet("key_set", emptySet()))
    }

    @Test
    fun migrate_fromPlainPrefs_toSafeBox_shouldCopyAllEntries() {
        val plainPrefs = context.getSharedPreferences("plain_prefs", Context.MODE_PRIVATE)

        plainPrefs.edit()
            .putString("username", "harrytmthy")
            .putInt("age", 30)
            .apply()

        SafeBoxMigrationHelper.migrate(plainPrefs, safeBox)

        assertEquals("harrytmthy", safeBox.getString("username", null))
        assertEquals(30, safeBox.getInt("age", -1))

        context.deleteSharedPreferences("plain_prefs")
    }
}
