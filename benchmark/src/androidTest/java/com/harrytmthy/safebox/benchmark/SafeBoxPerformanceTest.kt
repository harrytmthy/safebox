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

package com.harrytmthy.safebox.benchmark

import android.content.Context
import android.content.SharedPreferences
import androidx.benchmark.ExperimentalBenchmarkConfigApi
import androidx.benchmark.MicrobenchmarkConfig
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.harrytmthy.safebox.SafeBox
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File

/**
 * Measures creation, reads and commits for the selected library (`safebox` or `esp`).
 *
 * Initialization reuses the same filename within one process, preserving normal library caches.
 */
@Suppress("DEPRECATION")
@RunWith(JUnit4::class)
class SafeBoxPerformanceTest {

    @OptIn(ExperimentalBenchmarkConfigApi::class)
    @get:Rule
    val benchmarkRule = BenchmarkRule(
        MicrobenchmarkConfig(
            warmupCount = 20,
            measurementCount = 50,
        ),
    )

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var storage: Storage

    @Before
    fun setUp() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeNotNull(arguments.getString("benchmarkRun"))
        storage = when (arguments.getString("library")) {
            "safebox" -> Storage.SAFEBOX
            "esp" -> Storage.ENCRYPTED_SHARED_PREFERENCES
            else -> error("Pass -e library safebox or -e library esp.")
        }
    }

    @Test
    fun initialize() {
        measureInitialization(storage)
    }

    @Test
    fun get1Entry() {
        measureGet(storage, 1)
    }

    @Test
    fun commit1Entry() {
        measureCommit(storage, 1)
    }

    @Test
    fun get5Entries() {
        measureGet(storage, 5)
    }

    @Test
    fun commit5Entries() {
        measureCommit(storage, 5)
    }

    @Test
    fun get10Entries() {
        measureGet(storage, 10)
    }

    @Test
    fun get50Entries() {
        measureGet(storage, 50)
    }

    @Test
    fun get100Entries() {
        measureGet(storage, 100)
    }

    @Test
    fun commit10Entries() {
        measureCommit(storage, 10)
    }

    @Test
    fun commit50Entries() {
        measureCommit(storage, 50)
    }

    @Test
    fun commit100Entries() {
        measureCommit(storage, 100)
    }

    @Test
    fun commit5SingleEntries() {
        measureSingleCommits(storage, 5)
    }

    @Test
    fun commit10SingleEntries() {
        measureSingleCommits(storage, 10)
    }

    @Test
    fun commit50SingleEntries() {
        measureSingleCommits(storage, 50)
    }

    @Test
    fun commit100SingleEntries() {
        measureSingleCommits(storage, 100)
    }

    private fun measureInitialization(storage: Storage) {
        val prefs = createPreferences(storage)
        try {
            benchmarkRule.measureRepeated {
                createPreferences(storage)
            }
        } finally {
            resetPreferences(prefs, storage.fileName)
        }
    }

    private fun measureGet(storage: Storage, entryCount: Int) {
        withPreferences(storage) { prefs ->
            val editor = prefs.edit()
            repeat(entryCount) { editor.putInt(it.toString(), it) }
            editor.commit()
            benchmarkRule.measureRepeated {
                repeat(entryCount) { prefs.getInt(it.toString(), -1) }
            }
        }
    }

    private fun measureCommit(storage: Storage, entryCount: Int) {
        withPreferences(storage) { prefs ->
            benchmarkRule.measureRepeated {
                val editor = prefs.edit()
                repeat(entryCount) { editor.putInt(it.toString(), it) }
                editor.commit()
                runWithMeasurementDisabled {
                    prefs.edit().clear().commit()
                }
            }
        }
    }

    private fun measureSingleCommits(storage: Storage, entryCount: Int) {
        withPreferences(storage) { prefs ->
            benchmarkRule.measureRepeated {
                repeat(entryCount) {
                    prefs.edit().putInt(it.toString(), it).commit()
                }
                runWithMeasurementDisabled {
                    prefs.edit().clear().commit()
                }
            }
        }
    }

    private inline fun withPreferences(storage: Storage, block: (SharedPreferences) -> Unit) {
        val prefs = createPreferences(storage)
        try {
            prefs.edit().clear().commit()
            block(prefs)
        } finally {
            prefs.edit().clear().commit()
            resetPreferences(prefs, storage.fileName)
        }
    }

    private fun createPreferences(storage: Storage): SharedPreferences =
        when (storage) {
            Storage.SAFEBOX -> SafeBox.create(context, storage.fileName)
            Storage.ENCRYPTED_SHARED_PREFERENCES -> EncryptedSharedPreferences.create(
                context,
                storage.fileName,
                MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

    private fun resetPreferences(prefs: SharedPreferences, fileName: String) {
        if (prefs is SafeBox) {
            val engine = field(prefs, "engine")
            engine.javaClass.declaredMethods.first {
                it.name.startsWith("closeBlobStoreChannel")
            }.invoke(engine)
            val instances = SafeBox::class.java.getDeclaredField("instances").apply {
                isAccessible = true
            }.get(null) as MutableMap<*, *>
            instances.remove(fileName)
            for (suffix in listOf(".bin", ".key.bin")) {
                File(context.noBackupFilesDir, fileName + suffix).delete()
            }
        } else {
            context.deleteSharedPreferences(fileName)
        }
    }

    private fun field(owner: Any, name: String): Any =
        owner.javaClass.getDeclaredField(name).apply {
            isAccessible = true
        }.get(owner)!!

    private enum class Storage(val fileName: String) {
        SAFEBOX("benchmark_safebox"),
        ENCRYPTED_SHARED_PREFERENCES("benchmark_encrypted_shared_preferences"),
    }
}
