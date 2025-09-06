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
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties.BLOCK_MODE_GCM
import android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE
import android.security.keystore.KeyProperties.KEY_ALGORITHM_AES
import android.security.keystore.KeyProperties.PURPOSE_DECRYPT
import android.security.keystore.KeyProperties.PURPOSE_ENCRYPT
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harrytmthy.safebox.SafeBox.Companion.DEFAULT_KEY_ALIAS
import com.harrytmthy.safebox.SafeBox.Companion.DEFAULT_VALUE_KEYSTORE_ALIAS
import com.harrytmthy.safebox.cryptography.AesGcmCipherProvider
import com.harrytmthy.safebox.cryptography.ChaCha20CipherProvider
import com.harrytmthy.safebox.engine.SafeBoxEngine
import com.harrytmthy.safebox.keystore.SecureRandomKeyProvider
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
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.KeyGenerator
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

    private val legacyAlias = "abc"

    private val engines = HashMap<String, SafeBoxEngine>()

    private lateinit var safeBox: SafeBox

    @After
    fun tearDown() {
        cleanupResources()
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

        val anotherSafeBox = createSafeBox(fileName = "test_safebox")

        assertTrue(safeBox !== anotherSafeBox)
        assertTrue(safeBox === createSafeBox())
        File(context.noBackupFilesDir, "test_safebox").delete()
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
    fun getInt_afterKeyRotation_shouldReturnCorrectValue() {
        val aesGcmCipherProvider = AesGcmCipherProvider.create(aad = fileName.toByteArray())
        val keyProvider = SecureRandomKeyProvider.create(
            context = context,
            fileName = fileName,
            keySize = ChaCha20CipherProvider.KEY_SIZE,
            algorithm = ChaCha20CipherProvider.ALGORITHM,
            cipherProvider = aesGcmCipherProvider,
        )
        val keyCipherProvider = ChaCha20CipherProvider(keyProvider, deterministic = true)
        val valueCipherProvider = ChaCha20CipherProvider(keyProvider, deterministic = false)
        safeBox = SafeBox.create(
            context,
            fileName,
            keyCipherProvider,
            valueCipherProvider,
            UnconfinedTestDispatcher(),
        )
        safeBox.edit().putInt("key", 1).commit()

        keyProvider.rotateKey()

        assertEquals(-1, safeBox.getInt("key", -1))
    }

    @Test
    fun getInt_withLegacyAliasExist_shouldReturnCorrectValue() {
        ensureLegacyAliasInKeyStore()
        safeBox = createSafeBox()
        safeBox.edit().putInt("mk", 42).commit()
        removeSafeBoxInstance()

        safeBox = createSafeBox()
        val result = safeBox.getInt("mk", -1)

        assertEquals(42, result)
    }

    @Test
    fun getFloat_afterClear_withReusedEditor_shouldBeRetained() {
        safeBox = createSafeBox()
        val editor = safeBox.edit()
        editor.clear().commit()

        editor.putFloat("key1", 1.234f).commit()
        editor.putBoolean("key2", true).commit()

        assertEquals(1.234f, safeBox.getFloat("key1", 0f))
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
            key?.let { changedValues += safeBox.all[it] }
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

        val expectedKeyChanges = buildList {
            add("key0")
            val appOnRPlus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            val appTargetsRPlus = context.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.R
            if (appOnRPlus && appTargetsRPlus) {
                add(null)
            }
            add("key3")
            add("key2")
        }
        val expectedValueChanges = listOf(
            0.2f, // put Float
            setOf("SafeBox"), // put StringSet
            true, // put Boolean
        )
        assertContentEquals(expectedKeyChanges, changedKeys)
        assertContentEquals(expectedValueChanges, changedValues)
        safeBox.unregisterOnSharedPreferenceChangeListener(listener)
    }

    @Test
    fun listener_whenPuttingThenRemovingKey_shouldNotNotify() {
        safeBox = createSafeBox()
        val actualKeys = ArrayList<String?>()
        val actualValues = ArrayList<Int?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            actualKeys += key
            key?.let { actualValues += safeBox.getInt(it, -1) }
        }
        safeBox.registerOnSharedPreferenceChangeListener(listener)

        safeBox.edit()
            .putInt("key", 1)
            .remove("key")
            .commit()

        assertEquals(emptyList(), actualKeys)
        assertEquals(emptyList(), actualValues)
        safeBox.unregisterOnSharedPreferenceChangeListener(listener)
    }

    @Test
    fun listener_whenNothingRemoved_shouldNotNotify() {
        safeBox = createSafeBox()
        val actualKeys = ArrayList<String?>()
        val actualValues = ArrayList<Int?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            actualKeys += key
            key?.let { actualValues += safeBox.getInt(it, -1) }
        }
        safeBox.registerOnSharedPreferenceChangeListener(listener)

        safeBox.edit().remove("key").commit()

        assertEquals(emptyList(), actualKeys)
        assertEquals(emptyList(), actualValues)
        safeBox.unregisterOnSharedPreferenceChangeListener(listener)
    }

    @Test
    fun listener_withExistingKey_whenPuttingSameValues_shouldNotNotify() {
        safeBox = createSafeBox()
        safeBox.edit().putInt("key", 1).commit()
        val actualKeys = ArrayList<String?>()
        val actualValues = ArrayList<Int?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            actualKeys += key
            key?.let { actualValues += safeBox.getInt(it, -1) }
        }
        safeBox.registerOnSharedPreferenceChangeListener(listener)

        safeBox.edit().putInt("key", 1).commit()

        assertEquals(emptyList(), actualKeys)
        assertEquals(emptyList(), actualValues)
        safeBox.unregisterOnSharedPreferenceChangeListener(listener)
    }

    @Test
    fun listener_withExistingKey_whenCleared_shouldNotNotify() {
        safeBox = createSafeBox()
        safeBox.edit().putInt("key", 1).commit()
        val actualKeys = ArrayList<String?>()
        val actualValues = ArrayList<Int?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            actualKeys += key
            key?.let { safeBox.getInt(it, -1) }
        }
        safeBox.registerOnSharedPreferenceChangeListener(listener)

        safeBox.edit().clear().commit()

        val appOnRPlus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val appTargetsRPlus = context.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.R
        val expectedKeys: List<String?> = if (appOnRPlus && appTargetsRPlus) {
            listOf(null)
        } else {
            emptyList()
        }
        assertEquals(expectedKeys, actualKeys)
        assertEquals(emptyList(), actualValues)
        safeBox.unregisterOnSharedPreferenceChangeListener(listener)
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
    fun apply_whenRepeatedManyTimes_shouldReturnCorrectValues() {
        safeBox = createSafeBox(ioDispatcher = Dispatchers.IO)

        repeat(50) {
            safeBox.edit().apply {
                repeat(100) {
                    putInt(it.toString(), it).apply()
                }
            }
        }

        repeat(50) {
            repeat(100) {
                assertEquals(it, safeBox.getInt(it.toString(), -1))
            }
        }
    }

    @Test
    fun getBoolean_withMultipleInstances_shouldReturnCorrectValue() {
        safeBox = createSafeBox()
        val prefs = createSafeBox(legacyAlias)

        safeBox.edit().putBoolean("key", true).commit()
        prefs.edit().putBoolean("key", true).commit()

        assertEquals(true, safeBox.getBoolean("key", false))
        assertEquals(true, prefs.getBoolean("key", false))
    }

    private fun ensureLegacyAliasInKeyStore() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(legacyAlias)) return
        val keyGenerator = KeyGenerator.getInstance(KEY_ALGORITHM_AES, "AndroidKeyStore")
        keyGenerator.init(
            KeyGenParameterSpec.Builder(legacyAlias, PURPOSE_ENCRYPT or PURPOSE_DECRYPT)
                .setBlockModes(BLOCK_MODE_GCM)
                .setEncryptionPaddings(ENCRYPTION_PADDING_NONE)
                .build(),
        )
        keyGenerator.generateKey()
    }

    private fun removeSafeBoxInstance(fileName: String = this.fileName) {
        engines[fileName]?.let {
            it.closeBlobStoreChannel()
            engines.remove(fileName)
        }
        SafeBox.instances.remove(fileName)
    }

    private fun cleanupResources() {
        val iterator = engines.iterator()
        while (iterator.hasNext()) {
            val (fileName, engine) = iterator.next()
            engine.closeBlobStoreChannel()
            iterator.remove()
            File(context.noBackupFilesDir, "$fileName.bin").delete()
            File(context.noBackupFilesDir, "$fileName.key.bin").delete()
            SafeBox.instances.remove(fileName)
        }
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(DEFAULT_VALUE_KEYSTORE_ALIAS)
            deleteEntry(legacyAlias)
        }
        File(context.noBackupFilesDir, "$fileName.bin").delete()
        File(context.noBackupFilesDir, "$fileName.key.bin").delete()
        File(context.noBackupFilesDir, "$legacyAlias.key.bin").delete()
        File(context.noBackupFilesDir, "$DEFAULT_KEY_ALIAS.bin").delete()
    }

    private fun createSafeBox(
        fileName: String = this.fileName,
        ioDispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
        stateListener: SafeBoxStateListener? = null,
    ): SafeBox {
        val engine = SafeBoxEngine.create(
            context,
            fileName,
            ioDispatcher,
            stateListener,
        )
        engines[fileName] = engine
        return SafeBox.createInternal(fileName, stateListener, engine)
    }
}
