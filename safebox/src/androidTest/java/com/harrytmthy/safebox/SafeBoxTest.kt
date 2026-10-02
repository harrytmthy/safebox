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
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harrytmthy.safebox.SafeBox.Companion.DEFAULT_KEY_ALIAS
import com.harrytmthy.safebox.SafeBox.Companion.DEFAULT_VALUE_KEYSTORE_ALIAS
import com.harrytmthy.safebox.cryptography.CipherProvider
import com.harrytmthy.safebox.engine.SafeBoxEngine
import com.harrytmthy.safebox.extensions.toBytes
import com.harrytmthy.safebox.factory.SafeBoxCryptoFactory
import com.harrytmthy.safebox.storage.Bytes
import com.harrytmthy.safebox.storage.SafeBoxBlobStore
import com.harrytmthy.safebox.storage.SafeBoxRecoveryBlobStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.ReadOnlyBufferException
import java.nio.channels.ClosedChannelException
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
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
    fun getFloat_afterClear_withReusedEditor_shouldBeRetained() {
        safeBox = createSafeBox()
        val editor = safeBox.edit()
        editor.clear().commit()

        editor.putFloat("key1", 1.234f).commit()
        editor.putBoolean("key2", true).commit()

        assertEquals(1.234f, safeBox.getFloat("key1", 0f))
    }

    @Test
    fun getInt_shouldReturnPersistedValue() {
        safeBox = createSafeBox()
        var prefs = createSafeBox(legacyAlias)
        safeBox.edit().putInt("key", 1).commit()
        prefs.edit().putInt("key", 2).commit()
        engines[fileName]?.closeBlobStoreChannel()
        engines[legacyAlias]?.closeBlobStoreChannel()
        SafeBox.instances.remove(fileName)
        SafeBox.instances.remove(legacyAlias)

        safeBox = createSafeBox()
        prefs = createSafeBox(legacyAlias)

        assertEquals(1, safeBox.getInt("key", -1))
        assertEquals(2, prefs.getInt("key", -1))
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
    fun clear_afterReplacingDebounceJob_shouldNotRestorePendingValues() = runBlocking {
        val scheduler = TestCoroutineScheduler()
        safeBox = createSafeBox(ioDispatcher = StandardTestDispatcher(scheduler))
        scheduler.runCurrent()

        safeBox.edit().putString("name", "Luna").apply()
        scheduler.runCurrent()
        safeBox.edit().putString("name", "Luper").apply()
        scheduler.runCurrent()

        // Run queued writes without advancing the debounce timer while commit blocks.
        val committed = async(Dispatchers.IO) {
            safeBox.edit().clear().commit()
        }
        withTimeout(10.seconds) {
            while (!committed.isCompleted) {
                scheduler.runCurrent()
                delay(1)
            }
        }
        assertTrue(committed.await())
        assertNull(safeBox.getString("name", null))

        scheduler.advanceUntilIdle()
        safeBox = recreateSafeBox()
        assertNull(safeBox.getString("name", null))
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

    @Test
    fun write_whenPrimaryBlobUnavailable_shouldFallbackToRecovery_andSurviveRecreate() = runTest {
        safeBox = divertNextWriteToRecovery()

        assertTrue(safeBox.edit().putString(RECOVERY_KEY, STALE_VALUE).commit())
        assertEquals(1, loadRecoveryEntries().size)

        safeBox = recreateSafeBox()

        assertEquals(STALE_VALUE, safeBox.getString(RECOVERY_KEY, null))
    }

    @Test
    fun put_whenOverwritingRecoveredEntry_shouldRetainNewerValueAfterRecreate() = runTest {
        safeBox = divertNextWriteToRecovery()
        assertTrue(safeBox.edit().putString(RECOVERY_KEY, STALE_VALUE).commit())
        assertEquals(1, loadRecoveryEntries().size)
        safeBox = recreateSafeBox()
        assertEquals(STALE_VALUE, safeBox.getString(RECOVERY_KEY, null))

        assertTrue(safeBox.edit().putString(RECOVERY_KEY, NEWER_VALUE).commit())
        safeBox = recreateSafeBox()

        assertEquals(NEWER_VALUE, safeBox.getString(RECOVERY_KEY, null))
        assertTrue(loadRecoveryEntries().isEmpty())
    }

    @Test
    fun remove_whenEntryOnlyExistsInRecovery_shouldNotRestoreItAfterRecreate() = runTest {
        safeBox = divertNextWriteToRecovery()
        assertTrue(safeBox.edit().putString(RECOVERY_KEY, STALE_VALUE).commit())
        assertEquals(1, loadRecoveryEntries().size)
        safeBox = recreateSafeBox()
        assertEquals(STALE_VALUE, safeBox.getString(RECOVERY_KEY, null))

        assertTrue(safeBox.edit().remove(RECOVERY_KEY).commit())
        safeBox = recreateSafeBox()

        assertNull(safeBox.getString(RECOVERY_KEY, null))
        assertTrue(loadRecoveryEntries().isEmpty())
    }

    @Test
    fun clear_whenEntryOnlyExistsInRecovery_shouldNotRestoreItAfterRecreate() = runTest {
        safeBox = divertNextWriteToRecovery()
        assertTrue(safeBox.edit().putString(RECOVERY_KEY, STALE_VALUE).commit())
        assertEquals(1, loadRecoveryEntries().size)
        safeBox = recreateSafeBox()
        assertEquals(STALE_VALUE, safeBox.getString(RECOVERY_KEY, null))

        assertTrue(safeBox.edit().clear().commit())
        safeBox = recreateSafeBox()

        assertTrue(safeBox.all.isEmpty())
        assertTrue(loadRecoveryEntries().isEmpty())
    }

    @Test
    fun put_whenReplacementIsRejected_shouldKeepRecoveredValue() = runTest {
        safeBox = divertNextWriteToRecovery()
        assertTrue(safeBox.edit().putString(RECOVERY_KEY, STALE_VALUE).commit())
        assertEquals(1, loadRecoveryEntries().size)

        val oversizedValue = "x".repeat(2 * 1024 * 1024)
        assertFalse(safeBox.edit().putString(RECOVERY_KEY, oversizedValue).commit())
        safeBox = recreateSafeBox()

        assertEquals(STALE_VALUE, safeBox.getString(RECOVERY_KEY, null))
    }

    @Test
    fun put_whenReplayCouldNotRetireItsRecord_shouldStillRetireOnNextWrite() = runTest {
        safeBox = divertNextWriteToRecovery(secondPage = true)
        assertTrue(safeBox.edit().putString(RECOVERY_KEY, STALE_VALUE).commit())
        assertEquals(1, loadRecoveryEntries().size)
        assertFalse(safeBox.edit().remove(FIRST_FILLER_KEY).remove(SECOND_FILLER_KEY).commit())

        // Replay fits in page one, but reclaiming page two needs the closed channel.
        assertFailsWith<ClosedChannelException> {
            engines.getValue(fileName).replayRecoveryEntries()
        }
        assertEquals(1, loadRecoveryEntries().size)

        // The value is forced, but the pending page reclamation still fails.
        assertFalse(safeBox.edit().putString(RECOVERY_KEY, NEWER_VALUE).commit())
        safeBox = recreateSafeBox()

        assertEquals(NEWER_VALUE, safeBox.getString(RECOVERY_KEY, null))
        assertTrue(loadRecoveryEntries().isEmpty())
    }

    @Test
    fun clear_whenPrimaryClearFails_shouldRetainAcknowledgedRecoveryValue() = runTest {
        safeBox = divertNextWriteToRecovery()
        assertTrue(safeBox.edit().putString(RECOVERY_KEY, STALE_VALUE).commit())
        assertEquals(1, loadRecoveryEntries().size)

        assertFalse(safeBox.edit().clear().commit())
        safeBox = recreateSafeBox()

        assertTrue(
            safeBox.getString(RECOVERY_KEY, null) == STALE_VALUE,
            "A failed primary clear discarded the acknowledged recovery value",
        )
    }

    @Test
    fun put_afterRecoveryForceFails_shouldRetainLaterSuccessfulValue() = runTest {
        withForceFailureStore { fixture ->
            val recovery = fixture.store
            safeBox = createSafeBox(recoveryBlobStore = recovery)
            assertTrue(safeBox.edit().putString(FIRST_FILLER_KEY, STALE_VALUE).commit())
            engines.getValue(fileName).closeBlobStoreChannel()

            fixture.failForce = true
            assertFalse(safeBox.edit().putString(RECOVERY_KEY, STALE_VALUE).commit())
            assertFailsWith<IOException> { engines.getValue(fileName).replayRecoveryEntries() }
            fixture.failForce = false
            val attemptsBeforeReplay = fixture.forceAttempts
            engines.getValue(fileName).replayRecoveryEntries()
            assertTrue(fixture.forceAttempts > attemptsBeforeReplay)

            assertTrue(safeBox.edit().putString(RECOVERY_KEY, NEWER_VALUE).commit())
            safeBox = recreateSafeBox(recoveryBlobStore = recovery)

            assertEquals(NEWER_VALUE, safeBox.getString(RECOVERY_KEY, null))
            assertTrue(recovery.loadPersistedEntries(fileName.toBytes()).isEmpty())
        }
    }

    @Test
    fun remove_whenRetirementForceFails_shouldNotReplayRemovedValue() = runTest {
        assertFailedRetirementDoesNotReplay(clear = false)
    }

    @Test
    fun clear_whenRetirementForceFails_shouldNotReplayClearedValues() = runTest {
        assertFailedRetirementDoesNotReplay(clear = true)
    }

    private suspend fun assertFailedRetirementDoesNotReplay(clear: Boolean) {
        withForceFailureStore { fixture ->
            safeBox = divertNextWriteToRecovery(recoveryBlobStore = fixture.store)
            assertTrue(safeBox.edit().putString(RECOVERY_KEY, STALE_VALUE).commit())
            safeBox = recreateSafeBox(recoveryBlobStore = fixture.store)

            fixture.failForce = true
            val editor = safeBox.edit()
            if (clear) {
                editor.clear()
            } else {
                editor.remove(RECOVERY_KEY)
            }
            assertFalse(editor.commit())
            val attemptsBeforeReplay = fixture.forceAttempts
            assertFailsWith<IOException> { engines.getValue(fileName).replayRecoveryEntries() }
            assertTrue(fixture.forceAttempts > attemptsBeforeReplay)

            fixture.failForce = false
            engines.getValue(fileName).replayRecoveryEntries()
            safeBox = recreateSafeBox(recoveryBlobStore = fixture.store)

            assertFalse(safeBox.contains(RECOVERY_KEY), "Retired value was replayed")
            if (clear) {
                assertTrue(safeBox.all.isEmpty())
            } else {
                assertEquals(STALE_VALUE, safeBox.getString(FIRST_FILLER_KEY, null))
            }
            assertTrue(fixture.store.loadPersistedEntries(fileName.toBytes()).isEmpty())
        }
    }

    @Test
    fun put_whenRetirementForceFails_shouldNotDivertSuccessfulPrimaryWrite() = runTest {
        withForceFailureStore { fixture ->
            safeBox = divertNextWriteToRecovery(recoveryBlobStore = fixture.store)
            assertTrue(safeBox.edit().putString(RECOVERY_KEY, STALE_VALUE).commit())
            safeBox = recreateSafeBox(recoveryBlobStore = fixture.store)

            fixture.failForce = true
            assertFalse(safeBox.edit().putString(RECOVERY_KEY, NEWER_VALUE).commit())
            assertTrue(fixture.store.loadPersistedEntries(fileName.toBytes()).isEmpty())

            fixture.failForce = false
            engines.getValue(fileName).replayRecoveryEntries()
            safeBox = recreateSafeBox(recoveryBlobStore = fixture.store)

            assertEquals(NEWER_VALUE, safeBox.getString(RECOVERY_KEY, null))
            assertTrue(fixture.store.loadPersistedEntries(fileName.toBytes()).isEmpty())
        }
    }

    @Test
    fun put_afterFailedRetirement_shouldTrackNewFallbackValue() = runTest {
        withForceFailureStore { fixture ->
            safeBox = divertNextWriteToRecovery(recoveryBlobStore = fixture.store)
            assertTrue(safeBox.edit().putString(RECOVERY_KEY, STALE_VALUE).commit())
            safeBox = recreateSafeBox(recoveryBlobStore = fixture.store)
            fixture.failForce = true
            assertFalse(safeBox.edit().remove(RECOVERY_KEY).commit())

            engines.getValue(fileName).closeBlobStoreChannel()
            fixture.failForce = false
            val replacement = "replacement-".repeat(50_000)
            assertTrue(safeBox.edit().putString(RECOVERY_KEY, replacement).commit())
            assertEquals(1, fixture.store.loadPersistedEntries(fileName.toBytes()).size)
            assertTrue(safeBox.edit().remove(FIRST_FILLER_KEY).commit())
            engines.getValue(fileName).replayRecoveryEntries()
            safeBox = recreateSafeBox(recoveryBlobStore = fixture.store)

            assertEquals(replacement, safeBox.getString(RECOVERY_KEY, null))
            assertTrue(fixture.store.loadPersistedEntries(fileName.toBytes()).isEmpty())
        }
    }

    @Test
    fun replay_whenPrimaryFlushFails_shouldStillRetryPendingJournalFlush() = runTest {
        withForceFailureStore { fixture ->
            safeBox = divertNextWriteToRecovery(
                secondPage = true,
                recoveryBlobStore = fixture.store,
            )
            fixture.failForce = true
            assertFalse(safeBox.edit().putString(RECOVERY_KEY, STALE_VALUE).commit())
            assertFalse(safeBox.edit().remove(FIRST_FILLER_KEY).remove(SECOND_FILLER_KEY).commit())

            fixture.failForce = false
            val attemptsBeforeReplay = fixture.forceAttempts
            assertFailsWith<ClosedChannelException> {
                engines.getValue(fileName).replayRecoveryEntries()
            }

            assertTrue(fixture.forceAttempts > attemptsBeforeReplay)
            assertEquals(1, fixture.store.loadPersistedEntries(fileName.toBytes()).size)
        }
    }

    @Test
    fun clear_withSharedRecovery_shouldPreserveOtherFile() = runTest {
        val otherFileName = "other_recovery_test"
        val fillerValue = "f".repeat(1_000_000)
        val recoveryValue = "r".repeat(100_000)
        for (name in listOf(fileName, otherFileName)) {
            val prefs = createSafeBox(fileName = name)
            assertTrue(prefs.edit().putString(FIRST_FILLER_KEY, fillerValue).commit())
            engines.getValue(name).closeBlobStoreChannel()
            assertTrue(prefs.edit().putString(RECOVERY_KEY, recoveryValue).commit())
            assertEquals(
                1,
                SafeBoxRecoveryBlobStore.getOrCreate(context)
                    .loadPersistedEntries(name.toBytes()).size,
            )
        }

        safeBox = recreateSafeBox()
        assertTrue(safeBox.edit().clear().commit())
        safeBox = recreateSafeBox()
        val otherSafeBox = recreateSafeBox(fileName = otherFileName)

        assertTrue(safeBox.all.isEmpty())
        assertTrue(loadRecoveryEntries().isEmpty())
        assertEquals(recoveryValue, otherSafeBox.getString(RECOVERY_KEY, null))
        assertEquals(
            1,
            SafeBoxRecoveryBlobStore.getOrCreate(context)
                .loadPersistedEntries(otherFileName.toBytes()).size,
        )
    }

    @Test
    fun apply_whenBatchFailsToWrite_shouldNotifyFailedActionAndActualBatch() {
        val failures = FailureRecorder()
        safeBox = createSafeBox(failureListener = failures)
        safeBox.edit().putString("first", "1").putString("first", "2").apply()
        safeBox.edit().putString("oversized", "x".repeat(2 * 1024 * 1024)).apply()

        assertTrue(safeBox.edit().putString("next", "3").commit())

        val (primaryError, primaryTrace) = failures.awaitFailure()
        assertTrue(primaryError is IllegalStateException)
        assertEquals(
            expected = "SafeBox \"$fileName\" write: primary write failed\n  put oversized",
            actual = primaryTrace,
        )
        val (batchError, batchTrace) = failures.awaitFailure()
        assertTrue(batchError is IllegalStateException)
        assertEquals(
            expected = "SafeBox \"$fileName\" write: failed to write a recovery record\n" +
                "  batch: put first, put oversized",
            actual = batchTrace,
        )
        failures.assertNoFailure()
    }

    @Test
    fun commit_whenPendingApplyAndMemoryUpdateFail_shouldKeepTheirActionContexts() {
        val failures = FailureRecorder()
        val cause = IOException("Memory encryption failed")
        val valueCipherProvider = FaultyCipherProvider()
        safeBox = createSafeBox(
            cipherProviders = FaultyCipherProvider() to valueCipherProvider,
            failureListener = failures,
        )
        safeBox.edit().putString("oversized", "x".repeat(2 * 1024 * 1024)).apply()
        valueCipherProvider.encryptFailure = cause

        val thrown = assertFailsWith<IOException> {
            safeBox.edit().putString("new-action", "value").commit()
        }
        assertSame(cause, thrown)

        repeat(2) {
            val (error, trace) = failures.awaitFailure()
            assertTrue(error is IllegalStateException)
            assertTrue(trace.contains("put oversized"))
            assertFalse(trace.contains("new-action"))
        }
        val (error, trace) = failures.awaitFailure()
        assertSame(cause, error)
        assertTrue(trace.contains("put new-action"))
        assertFalse(trace.contains("oversized"))
        failures.assertNoFailure()
    }

    @Test
    fun replay_whenFailuresRepeat_shouldNotifyEachFailedEntryOnEveryPass() {
        val failures = FailureRecorder()
        safeBox = createSafeBox(failureListener = failures)
        assertTrue(safeBox.edit().putString(FIRST_FILLER_KEY, "f".repeat(900_000)).commit())
        val engine = engines.getValue(fileName)
        engine.closeBlobStoreChannel()
        for (key in listOf("first-recovery", "second-recovery")) {
            assertTrue(safeBox.edit().putString(key, "r".repeat(250_000)).commit())
            val (error, trace) = failures.awaitFailure()
            assertTrue(error is ClosedChannelException)
            assertTrue(trace.contains("primary write failed"))
            assertTrue(trace.contains("put $key"))
        }

        repeat(2) {
            runBlocking { engine.replayRecoveryEntries() }
            repeat(2) {
                val (error, trace) = failures.awaitFailure()
                assertTrue(error is ClosedChannelException)
                assertEquals("SafeBox \"$fileName\" recovery replay: primary write failed", trace)
            }
        }
        failures.assertNoFailure()
    }

    @Test
    fun commit_whenRecoveryForceFails_shouldNotifyPrimaryFailureBeforeRecoveryFailure() = runTest {
        withForceFailureStore { fixture ->
            val failures = FailureRecorder()
            safeBox = divertNextWriteToRecovery(
                recoveryBlobStore = fixture.store,
                failureListener = failures,
            )
            fixture.failForce = true

            assertFalse(safeBox.edit().putString(RECOVERY_KEY, STALE_VALUE).commit())

            val (primaryError, primaryTrace) = failures.awaitFailure()
            assertTrue(primaryError is ClosedChannelException)
            assertTrue(primaryTrace.contains("primary write failed"))
            assertTrue(primaryTrace.contains("put $RECOVERY_KEY"))
            val (recoveryError, recoveryTrace) = failures.awaitFailure()
            assertTrue(recoveryError is IOException)
            assertEquals("Injected recovery force failure", recoveryError.message)
            assertTrue(recoveryTrace.contains("write: failed to flush recovery storage"))
            assertTrue(recoveryTrace.contains("put $RECOVERY_KEY"))
            failures.assertNoFailure()
            fixture.failForce = false
        }
    }

    @Test
    fun getString_whenValueCannotBeDecrypted_shouldNotifyBeforeQueuedCleanup() = runTest {
        val failures = FailureRecorder()
        val valueCipherProvider = FaultyCipherProvider()
        safeBox = createSafeBox(
            ioDispatcher = StandardTestDispatcher(testScheduler),
            cipherProviders = FaultyCipherProvider() to valueCipherProvider,
            failureListener = failures,
        )
        runCurrent()
        assertTrue(safeBox.edit().putString("token", "secret").commit())
        val cause = AEADBadTagException()
        valueCipherProvider.decryptFailure = cause

        assertNull(safeBox.getString("token", null))

        val (error, trace) = failures.awaitFailure()
        assertSame(cause, error)
        assertTrue(trace.contains("read: failed to decrypt a preference value"))
        assertTrue(safeBox.contains("token"))
        runCurrent()
        assertFalse(safeBox.contains("token"))
        val (cleanupCause, cleanupTrace) = failures.awaitFailure()
        assertSame(cause, cleanupCause)
        assertEquals("SafeBox \"$fileName\" cleanup: removed 1 unreadable record", cleanupTrace)
        failures.assertNoFailure()
    }

    @Test
    fun cleanup_whenVerificationThrowsANewException_shouldRetainTheReadFailureAsCause() {
        val failures = FailureRecorder()
        val scheduler = TestCoroutineScheduler()
        val valueCipher = FaultyCipherProvider()
        safeBox = createSafeBox(
            ioDispatcher = StandardTestDispatcher(scheduler),
            cipherProviders = FaultyCipherProvider() to valueCipher,
            failureListener = failures,
        )
        scheduler.runCurrent()
        assertTrue(safeBox.edit().putInt("counter", 1).commit())
        valueCipher.freshAuthenticationFailures = true

        assertEquals(-1, safeBox.getInt("counter", -1))
        val (cause, _) = failures.awaitFailure()
        scheduler.runCurrent()
        val (cleanupCause, trace) = failures.awaitFailure()

        assertSame(cause, cleanupCause)
        assertEquals("SafeBox \"$fileName\" cleanup: removed 1 unreadable record", trace)
        failures.assertNoFailure()
    }

    @Test
    fun getAll_whenKeyAuthenticationFails_shouldNotifyAndRemoveTheRecord() {
        val failures = FailureRecorder()
        val keyCipherProvider = FaultyCipherProvider()
        safeBox = createSafeBox(
            cipherProviders = keyCipherProvider to FaultyCipherProvider(),
            failureListener = failures,
        )
        assertTrue(safeBox.edit().putString("token", "secret").commit())
        val cause = AEADBadTagException()
        keyCipherProvider.decryptFailure = cause

        assertTrue(safeBox.all.isEmpty())

        val (error, trace) = failures.awaitFailure()
        assertSame(cause, error)
        assertTrue(trace.contains("read: failed to decrypt a preference key"))
        assertNull(safeBox.getString("token", null))
        val (cleanupCause, cleanupTrace) = failures.awaitFailure()
        assertSame(cause, cleanupCause)
        assertTrue(cleanupTrace.contains("cleanup: removed 1 unreadable record"))
        failures.assertNoFailure()
    }

    @Test
    fun commit_whenExistingValueCannotBeDecrypted_shouldReportWriteContext() {
        val failures = FailureRecorder()
        val valueCipher = FaultyCipherProvider()
        safeBox = createSafeBox(
            cipherProviders = FaultyCipherProvider() to valueCipher,
            failureListener = failures,
        )
        assertTrue(safeBox.edit().putInt("counter", 1).commit())
        val cause = IOException("Temporary value decryption failure")
        valueCipher.decryptFailure = cause

        assertSame(
            cause,
            assertFailsWith<IOException> {
                safeBox.edit().putInt("counter", 2).commit()
            },
        )
        val (error, trace) = failures.awaitFailure()
        assertSame(cause, error)
        assertEquals(
            "SafeBox \"$fileName\" write: failed to decrypt a preference value\n  put counter",
            trace,
        )
        failures.assertNoFailure()
        valueCipher.decryptFailure = null
        assertEquals(1, safeBox.getInt("counter", -1))
    }

    @Test
    fun load_whenSeveralKeysCannotBeDecrypted_shouldReportConfirmedCleanupCount() {
        val keyCipher = FaultyCipherProvider()
        val providers = keyCipher to FaultyCipherProvider()
        safeBox = createSafeBox(cipherProviders = providers)
        assertTrue(
            actual = safeBox.edit().putInt("first", 1).putInt("second", 2).putInt("third", 3)
                .commit(),
        )
        engines.getValue(fileName).closeBlobStoreChannel()
        SafeBox.instances.remove(fileName)
        val cause = AEADBadTagException("Stored keys failed authentication")
        keyCipher.decryptFailure = cause
        val scheduler = TestCoroutineScheduler()
        val failures = FailureRecorder()
        safeBox = createSafeBox(
            ioDispatcher = StandardTestDispatcher(scheduler),
            cipherProviders = providers,
            failureListener = failures,
        )
        scheduler.runCurrent()

        repeat(3) {
            val (error, trace) = failures.awaitFailure()
            assertSame(cause, error)
            assertEquals(
                expected = "SafeBox \"$fileName\" initial load: failed to decrypt a preference key",
                actual = trace,
            )
        }
        val (error, trace) = failures.awaitFailure()
        assertSame(cause, error)
        assertEquals(
            "SafeBox \"$fileName\" cleanup: removed 3 unreadable records",
            trace,
        )
        failures.assertNoFailure()
        keyCipher.decryptFailure = null
        engines.getValue(fileName).closeBlobStoreChannel()
        SafeBox.instances.remove(fileName)
        safeBox = createSafeBox(cipherProviders = providers)
        assertTrue(safeBox.all.isEmpty())
    }

    @Test
    fun cleanup_whenPrimaryFlushFails_shouldNotReportConfirmedRemoval() {
        val valueCipher = FaultyCipherProvider()
        val failures = FailureRecorder()
        val scheduler = TestCoroutineScheduler()
        safeBox = createSafeBox(
            ioDispatcher = StandardTestDispatcher(scheduler),
            cipherProviders = FaultyCipherProvider() to valueCipher,
            failureListener = failures,
        )
        scheduler.runCurrent()
        assertTrue(safeBox.edit().putString("first", "a".repeat(600_000)).commit())
        assertTrue(safeBox.edit().putString("second", "b".repeat(600_000)).commit())
        engines.getValue(fileName).closeBlobStoreChannel()
        val cause = AEADBadTagException("Value authentication failed")
        valueCipher.decryptFailure = cause

        assertNull(safeBox.getString("first", null))
        scheduler.runCurrent()

        val (error, detectionTrace) = failures.awaitFailure()
        assertSame(cause, error)
        assertTrue(detectionTrace.contains("read: failed to decrypt a preference value"))
        val (flushError, flushTrace) = failures.awaitFailure()
        assertTrue(flushError is ClosedChannelException)
        assertEquals("SafeBox \"$fileName\" cleanup: failed to flush primary storage", flushTrace)
        failures.assertNoFailure()
    }

    @Test
    fun cleanup_whenRecoveryRetirementFails_shouldNotReportConfirmedRemoval() = runTest {
        withForceFailureStore { fixture ->
            val keyCipher = FaultyCipherProvider()
            val providers = keyCipher to FaultyCipherProvider()
            safeBox = createSafeBox(cipherProviders = providers, recoveryBlobStore = fixture.store)
            assertTrue(safeBox.edit().putInt("bad", 1).commit())
            engines.getValue(fileName).closeBlobStoreChannel()
            val primary = SafeBoxBlobStore.create(context, fileName)
            val records = primary.loadPersistedEntries()
            primary.closeWhenIdle()
            val key = "bad".toByteArray().toBytes()
            fixture.store.write(fileName.toBytes(), key, records.getValue(key), forceNow = true)
            SafeBox.instances.remove(fileName)
            keyCipher.unreadableKey = "bad"
            fixture.failForce = true
            val failures = FailureRecorder()
            safeBox = createSafeBox(
                ioDispatcher = StandardTestDispatcher(testScheduler),
                cipherProviders = providers,
                recoveryBlobStore = fixture.store,
                failureListener = failures,
            )
            runCurrent()

            val (cause, detectionTrace) = failures.awaitFailure()
            assertTrue(cause is AEADBadTagException)
            assertTrue(detectionTrace.contains("initial load: failed to decrypt a preference key"))
            val (cleanupError, cleanupTrace) = failures.awaitFailure()
            assertTrue(cleanupError is IOException)
            assertEquals("Injected recovery force failure", cleanupError.message)
            assertTrue(cleanupTrace.contains("cleanup: failed to remove a recovery record"))
            failures.assertNoFailure()
            fixture.failForce = false
        }
    }

    @Test
    fun commit_whenFailureListenerThrows_shouldContinueDeliveringFailures() {
        val attempts = CountDownLatch(4)
        safeBox = createSafeBox(failureListener = { _, _ ->
            attempts.countDown()
            error("Listener failure")
        })

        repeat(2) {
            assertFalse(safeBox.edit().putString("oversized", "x".repeat(2 * 1024 * 1024)).commit())
        }

        assertTrue(attempts.await(5, TimeUnit.SECONDS))
        assertTrue(safeBox.edit().putString("next", "value").commit())
    }

    @Test
    fun commit_whenMemoryValueEncryptionFails_shouldNotifyAndPropagateOriginalException() {
        val failures = FailureRecorder()
        val cause = IOException("Injected encryption failure")
        val valueCipherProvider = FaultyCipherProvider().apply { encryptFailure = cause }
        safeBox = createSafeBox(
            cipherProviders = FaultyCipherProvider() to valueCipherProvider,
            failureListener = failures,
        )

        val thrown = assertFailsWith<IOException> {
            safeBox.edit().putString("token", "secret-value").commit()
        }

        val (error, trace) = failures.awaitFailure()
        assertSame(cause, thrown)
        assertSame(cause, error)
        assertTrue(trace.contains("failed to encrypt a preference value"))
        assertTrue(trace.contains("put token"))
        assertFalse(trace.contains("secret-value"))
        failures.assertNoFailure()
        val nextCause = IOException("Next encryption failure")
        valueCipherProvider.encryptFailure = nextCause
        assertFailsWith<IOException> { safeBox.edit().putString("next", "value").commit() }
        assertSame(nextCause, failures.awaitFailure().first)
    }

    @Test
    fun commit_shouldReuseEncryptedMutationForPersistence() {
        val keyCipher = FaultyCipherProvider()
        val valueCipher = FaultyCipherProvider()
        safeBox = createSafeBox(cipherProviders = keyCipher to valueCipher)

        assertTrue(safeBox.edit().putInt("counter", 1).commit())
        assertEquals(1, keyCipher.encryptCalls)
        assertEquals(1, valueCipher.encryptCalls)
        assertTrue(safeBox.edit().putInt("counter", 2).commit())
        assertEquals(1, keyCipher.encryptCalls)
        assertEquals(2, valueCipher.encryptCalls)
        assertTrue(safeBox.edit().putInt("counter", 2).commit())
        assertEquals(1, keyCipher.encryptCalls)
        assertEquals(2, valueCipher.encryptCalls)

        engines.getValue(fileName).closeBlobStoreChannel()
        SafeBox.instances.remove(fileName)
        safeBox = createSafeBox(cipherProviders = keyCipher to valueCipher)
        assertEquals(2, safeBox.getInt("counter", -1))
        val beforeRemove = keyCipher.encryptCalls
        assertTrue(safeBox.edit().remove("counter").commit())
        assertEquals(beforeRemove, keyCipher.encryptCalls)
    }

    @Test
    fun reads_afterReopen_shouldUseLoadedRecordIdentityWithoutEncryptingKeys() {
        val keyCipher = VersionedKeyCipherProvider()
        val valueCipher = FaultyCipherProvider()
        safeBox = createSafeBox(cipherProviders = keyCipher to valueCipher)
        assertTrue(safeBox.edit().putInt("account_🥹", 7).commit())
        engines.getValue(fileName).closeBlobStoreChannel()
        SafeBox.instances.remove(fileName)
        keyCipher.version = "new"
        safeBox = createSafeBox(cipherProviders = keyCipher to valueCipher)

        repeat(10) {
            assertEquals(7, safeBox.getInt("account_🥹", -1))
            assertTrue(safeBox.contains("account_🥹"))
            assertFalse(safeBox.contains("missing"))
        }
        assertEquals(7, safeBox.all["account_🥹"])
        assertEquals(1, keyCipher.encryptCalls)
        assertTrue(safeBox.edit().putInt("account_🥹", 8).commit())
        assertEquals(1, keyCipher.encryptCalls)

        engines.getValue(fileName).closeBlobStoreChannel()
        SafeBox.instances.remove(fileName)
        safeBox = createSafeBox(cipherProviders = keyCipher to valueCipher)
        assertEquals(8, safeBox.getInt("account_🥹", -1))
        assertEquals(1, safeBox.all.size)
    }

    @Test
    fun apply_removeThenReinsertBeforePersistence_shouldInheritPendingRecordIdentity() {
        val keyCipher = VersionedKeyCipherProvider()
        val valueCipher = FaultyCipherProvider()
        safeBox = createSafeBox(cipherProviders = keyCipher to valueCipher)
        assertTrue(safeBox.edit().putInt("counter", 1).commit())
        engines.getValue(fileName).closeBlobStoreChannel()
        SafeBox.instances.remove(fileName)
        val scheduler = TestCoroutineScheduler()
        keyCipher.version = "new"
        safeBox = createSafeBox(
            ioDispatcher = StandardTestDispatcher(scheduler),
            cipherProviders = keyCipher to valueCipher,
        )
        scheduler.runCurrent()

        safeBox.edit().remove("counter").apply()
        safeBox.edit().putInt("counter", 2).apply()
        assertEquals(2, safeBox.getInt("counter", -1))
        assertEquals(1, keyCipher.encryptCalls)
        scheduler.advanceUntilIdle()
        engines.getValue(fileName).closeBlobStoreChannel()
        val store = SafeBoxBlobStore.create(context, fileName)
        val records = runBlocking { store.loadPersistedEntries() }
        assertEquals(setOf("old:counter".toByteArray().toBytes()), records.keys)
        runBlocking { store.closeWhenIdle() }

        SafeBox.instances.remove(fileName)
        safeBox = createSafeBox(cipherProviders = keyCipher to valueCipher)
        assertEquals(2, safeBox.getInt("counter", -1))
    }

    @Test
    fun apply_beforeInitialIndexReady_shouldWaitAndUpdateTheExistingRecord() = runBlocking {
        val keyCipher = VersionedKeyCipherProvider()
        val valueCipher = FaultyCipherProvider()
        safeBox = createSafeBox(cipherProviders = keyCipher to valueCipher)
        assertTrue(safeBox.edit().putInt("counter", 1).commit())
        engines.getValue(fileName).closeBlobStoreChannel()
        SafeBox.instances.remove(fileName)
        val scheduler = TestCoroutineScheduler()
        keyCipher.version = "new"
        safeBox = createSafeBox(
            ioDispatcher = StandardTestDispatcher(scheduler),
            cipherProviders = keyCipher to valueCipher,
        )
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val write = async(Dispatchers.IO) {
            started.countDown()
            safeBox.edit().putInt("counter", 2).apply()
            finished.countDown()
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
        } finally {
            scheduler.runCurrent()
        }
        withTimeout(10.seconds) { write.await() }
        assertEquals(2, safeBox.getInt("counter", -1))
        assertEquals(1, keyCipher.encryptCalls)
        scheduler.advanceUntilIdle()
        engines.getValue(fileName).closeBlobStoreChannel()
        SafeBox.instances.remove(fileName)
        safeBox = createSafeBox(cipherProviders = keyCipher to valueCipher)
        assertEquals(2, safeBox.getInt("counter", -1))
        assertEquals(1, safeBox.all.size)
    }

    @Test
    fun load_whenKeyProviderFails_shouldAllowLaterWritesAndPreserveUntouchedRecords() {
        val keyCipher = FaultyCipherProvider()
        val valueCipher = FaultyCipherProvider()
        safeBox = createSafeBox(cipherProviders = keyCipher to valueCipher)
        assertTrue(safeBox.edit().putInt("counter", 1).putInt("untouched", 8).commit())
        engines.getValue(fileName).closeBlobStoreChannel()
        SafeBox.instances.remove(fileName)
        val cause = IOException("Key provider temporarily unavailable")
        keyCipher.decryptFailure = cause
        val failures = FailureRecorder()
        safeBox = createSafeBox(
            cipherProviders = keyCipher to valueCipher,
            failureListener = failures,
        )

        assertFalse(safeBox.contains("counter"))
        assertEquals(-1, safeBox.getInt("counter", -1))
        assertSame(cause, failures.awaitFailure().first)
        failures.assertNoFailure()
        safeBox.edit().putInt("added", 3).apply()
        assertEquals(3, safeBox.getInt("added", -1))
        keyCipher.decryptFailure = null
        assertTrue(safeBox.edit().putInt("counter", 2).commit())
        assertEquals(2, safeBox.getInt("counter", -1))

        engines.getValue(fileName).closeBlobStoreChannel()
        SafeBox.instances.remove(fileName)
        safeBox = createSafeBox(cipherProviders = keyCipher to valueCipher)
        assertEquals(mapOf("counter" to 2, "untouched" to 8, "added" to 3), safeBox.all)
    }

    @Test
    fun load_whenRawScanFails_shouldRetryBeforePersistenceWithoutReplacingMemory() {
        val keyCipher = FaultyCipherProvider()
        val valueCipher = FaultyCipherProvider()
        val providers = keyCipher to valueCipher
        val oldValue = "b".repeat(600_000)
        val newValue = "c".repeat(600_000)
        safeBox = createSafeBox(cipherProviders = providers)
        assertTrue(safeBox.edit().putString("first", "a".repeat(600_000)).commit())
        assertTrue(safeBox.edit().putString("second", oldValue).commit())
        engines.getValue(fileName).closeBlobStoreChannel()
        SafeBox.instances.remove(fileName)
        RandomAccessFile(File(context.noBackupFilesDir, "$fileName.bin"), "rw").use {
            it.writeShort(-1)
        }
        val scheduler = TestCoroutineScheduler()
        val failures = FailureRecorder()
        safeBox = createSafeBox(
            ioDispatcher = StandardTestDispatcher(scheduler),
            cipherProviders = providers,
            failureListener = failures,
        )
        val engine = engines.getValue(fileName)
        val store = SafeBoxEngine::class.java.getDeclaredField("blobStore").apply {
            isAccessible = true
        }.get(engine) as SafeBoxBlobStore

        @Suppress("UNCHECKED_CAST")
        val buffers = SafeBoxBlobStore::class.java.getDeclaredField("buffers").apply {
            isAccessible = true
        }.get(store) as ArrayList<MappedByteBuffer>
        val firstBuffer = buffers[0]
        buffers[0] = firstBuffer.asReadOnlyBuffer() as MappedByteBuffer
        try {
            scheduler.runCurrent()
            val (loadError, loadTrace) = failures.awaitFailure()
            assertTrue(loadError is ReadOnlyBufferException)
            assertTrue(loadTrace.contains("failed to load"))
            assertNull(safeBox.getString("second", null))

            safeBox.edit().putString("second", newValue).apply()
            scheduler.advanceUntilIdle()
            val (writeError, writeTrace) = failures.awaitFailure()
            assertTrue(writeError is ReadOnlyBufferException)
            assertTrue(writeTrace.contains("write: failed to load primary storage"))
            assertEquals(newValue, safeBox.getString("second", null))
        } finally {
            buffers[0] = firstBuffer
        }
        assertTrue(safeBox.edit().putString("second", newValue).commit())
        assertEquals(newValue, safeBox.getString("second", null))
        failures.assertNoFailure()

        engines.getValue(fileName).closeBlobStoreChannel()
        SafeBox.instances.remove(fileName)
        safeBox = createSafeBox(cipherProviders = providers)
        assertEquals(newValue, safeBox.getString("second", null))
        assertEquals(1, safeBox.all.size)
    }

    @Test
    fun load_whenKeyAuthenticationFails_shouldRemoveOnlyThatRecordIncludingRecovery() {
        val keyCipher = FaultyCipherProvider()
        val valueCipher = FaultyCipherProvider()
        safeBox = createSafeBox(cipherProviders = keyCipher to valueCipher)
        assertTrue(safeBox.edit().putInt("bad", 1).putInt("good", 2).commit())
        engines.getValue(fileName).closeBlobStoreChannel()
        val primary = SafeBoxBlobStore.create(context, fileName)
        val records = runBlocking { primary.loadPersistedEntries() }
        runBlocking { primary.closeWhenIdle() }
        val badKey = "bad".toByteArray().toBytes()
        val recovery = SafeBoxRecoveryBlobStore.getOrCreate(context)
        runBlocking {
            recovery.write(fileName.toBytes(), badKey, records.getValue(badKey), forceNow = true)
        }
        SafeBox.instances.remove(fileName)
        keyCipher.unreadableKey = "bad"
        val scheduler = TestCoroutineScheduler()
        val failures = FailureRecorder()
        safeBox = createSafeBox(
            ioDispatcher = StandardTestDispatcher(scheduler),
            cipherProviders = keyCipher to valueCipher,
            failureListener = failures,
        )
        scheduler.runCurrent()

        assertFalse(safeBox.contains("bad"))
        assertEquals(2, safeBox.getInt("good", -1))
        val (keyCause, keyTrace) = failures.awaitFailure()
        assertTrue(keyCause is AEADBadTagException)
        assertTrue(keyTrace.contains("initial load: failed to decrypt a preference key"))
        val (cleanupCause, cleanupTrace) = failures.awaitFailure()
        assertSame(keyCause, cleanupCause)
        assertTrue(cleanupTrace.contains("cleanup: removed 1 unreadable record"))
        failures.assertNoFailure()
        assertFalse(
            actual = runBlocking { recovery.loadPersistedEntries(fileName.toBytes()) }
                .containsKey(badKey),
        )

        engines.getValue(fileName).closeBlobStoreChannel()
        SafeBox.instances.remove(fileName)
        keyCipher.unreadableKey = null
        safeBox = createSafeBox(cipherProviders = keyCipher to valueCipher)
        assertEquals(mapOf("good" to 2), safeBox.all)
    }

    @Test
    fun apply_whenMutationsMerge_shouldPersistTheirPreparedValues() {
        val scheduler = TestCoroutineScheduler()
        safeBox = createSafeBox(ioDispatcher = StandardTestDispatcher(scheduler))
        scheduler.runCurrent()

        safeBox.edit().putInt("counter", 1).putInt("removed", 1).apply()
        safeBox.edit().putInt("counter", 2).remove("removed").apply()
        scheduler.advanceUntilIdle()
        safeBox = recreateSafeBox()

        assertEquals(2, safeBox.getInt("counter", -1))
        assertFalse(safeBox.contains("removed"))
    }

    @Test
    fun put_whenUnchangedAfterRecoveryForceFailure_shouldRetryPersistence() = runTest {
        withForceFailureStore { fixture ->
            safeBox = divertNextWriteToRecovery(recoveryBlobStore = fixture.store)
            fixture.failForce = true
            assertFalse(safeBox.edit().putString(RECOVERY_KEY, STALE_VALUE).commit())
            val attempts = fixture.forceAttempts

            fixture.failForce = false
            assertTrue(safeBox.edit().putString(RECOVERY_KEY, STALE_VALUE).commit())
            assertTrue(fixture.forceAttempts > attempts)
            safeBox = recreateSafeBox(recoveryBlobStore = fixture.store)

            assertEquals(STALE_VALUE, safeBox.getString(RECOVERY_KEY, null))
        }
    }

    @Test
    fun commit_fromFailureListener_shouldFinishWithoutWaitingForItsOwnDelivery() {
        val completed = CountDownLatch(1)
        val result = AtomicReference<Result<Boolean>>()
        safeBox = createSafeBox(failureListener = { _, trace ->
            if (trace.contains("primary write failed")) {
                result.set(runCatching { safeBox.edit().putString("next", "value").commit() })
                completed.countDown()
            }
        })

        assertFalse(safeBox.edit().putString("oversized", "x".repeat(2 * 1024 * 1024)).commit())

        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertTrue(result.get().getOrThrow())
        assertEquals("value", safeBox.getString("next", null))
    }

    @Test
    fun commit_whenFailureListenerBlocks_shouldFinishBeforeListenerReturns() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val result = AtomicReference<Result<Boolean>>()
        safeBox = createSafeBox(failureListener = { _, _ ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        })
        val writer = Thread {
            result.set(
                runCatching {
                    safeBox.edit().putString("oversized", "x".repeat(2 * 1024 * 1024)).commit()
                },
            )
            completed.countDown()
        }
        writer.start()

        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertFalse(result.get().getOrThrow())
            assertTrue(safeBox.edit().putString("next", "value").commit())
        } finally {
            release.countDown()
            writer.join(5000)
        }
    }

    @Test
    fun commit_whenListenerIsBusy_shouldKeepAcceptedFailuresOrdered() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val activeCallbacks = AtomicInteger()
        val callbacksOverlapped = AtomicBoolean(false)
        val failures = FailureRecorder()
        val first = IOException("First failure")
        val cipher = FaultyCipherProvider().apply { encryptFailure = first }
        safeBox = createSafeBox(
            cipherProviders = FaultyCipherProvider() to cipher,
            failureListener = { error, trace ->
                if (activeCallbacks.incrementAndGet() > 1) {
                    callbacksOverlapped.set(true)
                }
                try {
                    if (error === first) {
                        entered.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    }
                    failures.onFailure(error, trace)
                } finally {
                    activeCallbacks.decrementAndGet()
                }
            },
        )
        assertFailsWith<IOException> { safeBox.edit().putInt("key", 0).commit() }
        val additionalFailures = List(40) { IOException("Failure $it") }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            for (cause in additionalFailures) {
                cipher.encryptFailure = cause
                assertFailsWith<IOException> { safeBox.edit().putInt("key", 1).commit() }
            }
            failures.assertNoFailure()
        } finally {
            release.countDown()
        }

        assertSame(first, failures.awaitFailure().first)
        for (cause in additionalFailures.take(16)) {
            val (error, trace) = failures.awaitFailure()
            assertSame(cause, error)
            assertEquals(
                expected = "SafeBox \"$fileName\" write: failed to encrypt a preference value\n" +
                    "  put key",
                actual = trace,
            )
        }
        assertFalse(callbacksOverlapped.get())
        failures.assertNoFailure()
        val next = IOException("After draining")
        cipher.encryptFailure = next
        assertFailsWith<IOException> { safeBox.edit().putInt("key", 2).commit() }
        val (error, trace) = failures.awaitFailure()
        assertSame(next, error)
        assertEquals(
            expected = "SafeBox \"$fileName\" write: failed to encrypt a preference value\n" +
                "  put key",
            actual = trace,
        )
    }

    @Test
    fun commit_withRepeatedEdits_shouldNotifyOnlyFinalActionAndResetReusedEditor() {
        val failures = FailureRecorder()
        safeBox = createSafeBox(failureListener = failures)
        val editor = safeBox.edit()
        repeat(10_000) { editor.putString("first", "value") }
        editor.putString("first", "x".repeat(2 * 1024 * 1024))

        assertFalse(editor.commit())

        val (_, firstTrace) = failures.awaitFailure()
        assertEquals("SafeBox \"$fileName\" write: primary write failed\n  put first", firstTrace)
        assertTrue(failures.awaitFailure().second.contains("failed to write"))
        assertFalse(editor.putString("second", "x".repeat(2 * 1024 * 1024)).commit())
        val (_, secondTrace) = failures.awaitFailure()
        assertTrue(secondTrace.contains("put second"))
        assertFalse(secondTrace.contains("first"))
        assertFalse(secondTrace.contains("omitted"))
        assertTrue(failures.awaitFailure().second.contains("failed to write"))
        failures.assertNoFailure()
    }

    @Test
    fun create_withCachedInstance_shouldKeepOriginalFailureListener() {
        val originalFailures = FailureRecorder()
        val replacementFailures = FailureRecorder()
        safeBox = createSafeBox(failureListener = originalFailures)

        val existing = SafeBox.create(
            context,
            fileName,
            FaultyCipherProvider(),
            FaultyCipherProvider(),
            failureListener = replacementFailures,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        assertSame(safeBox, existing)
        assertFalse(existing.edit().putString("oversized", "x".repeat(2 * 1024 * 1024)).commit())
        val (_, primaryTrace) = originalFailures.awaitFailure()
        assertTrue(primaryTrace.contains("primary write failed"))
        assertTrue(originalFailures.awaitFailure().second.contains("failed to write"))
        replacementFailures.assertNoFailure()
    }

    @Test
    fun commit_whenEncryptionCancelled_shouldPropagateWithoutNotifyingCancellation() {
        val failures = FailureRecorder()
        val cancelled = CancellationException("Operation cancelled")
        val valueCipherProvider = FaultyCipherProvider().apply { encryptFailure = cancelled }
        safeBox = createSafeBox(
            cipherProviders = FaultyCipherProvider() to valueCipherProvider,
            failureListener = failures,
        )

        val thrown = assertFailsWith<CancellationException> {
            safeBox.edit().putString("cancelled", "value").commit()
        }
        assertSame(cancelled, thrown)
        val cause = IOException("Injected encryption failure")
        valueCipherProvider.encryptFailure = cause
        assertFailsWith<IOException> { safeBox.edit().putString("failed", "value").commit() }

        assertSame(cause, failures.awaitFailure().first)
        failures.assertNoFailure()
    }

    private fun divertNextWriteToRecovery(
        secondPage: Boolean = false,
        recoveryBlobStore: SafeBoxRecoveryBlobStore =
            SafeBoxRecoveryBlobStore.getOrCreate(context),
        failureListener: SafeBox.FailureListener? = null,
    ): SafeBox {
        val safeBox = createSafeBox(
            recoveryBlobStore = recoveryBlobStore,
            failureListener = failureListener,
        )
        // Force page growth: closing a channel does not invalidate existing mappings.
        assertTrue(safeBox.edit().putString(FIRST_FILLER_KEY, STALE_VALUE).commit())
        if (secondPage) {
            assertTrue(safeBox.edit().putString(SECOND_FILLER_KEY, STALE_VALUE).commit())
        }
        engines[fileName]?.closeBlobStoreChannel()
        return safeBox
    }

    private fun recreateSafeBox(
        fileName: String = this.fileName,
        recoveryBlobStore: SafeBoxRecoveryBlobStore =
            SafeBoxRecoveryBlobStore.getOrCreate(context),
    ): SafeBox {
        engines[fileName]?.closeBlobStoreChannel()
        SafeBox.instances.remove(fileName)
        return createSafeBox(fileName = fileName, recoveryBlobStore = recoveryBlobStore)
    }

    private suspend fun loadRecoveryEntries(): Map<Bytes, ByteArray> =
        SafeBoxRecoveryBlobStore.getOrCreate(context).loadPersistedEntries(fileName.toBytes())

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
        cleanupRecoveryJournal()
    }

    private fun cleanupRecoveryJournal() {
        runBlocking {
            SafeBoxRecoveryBlobStore.getOrCreate(context).closeWhenIdle()
        }
        SafeBoxRecoveryBlobStore.removeInstance()
        File(context.noBackupFilesDir, "${SafeBoxRecoveryBlobStore.FILE_NAME}.bin").delete()
    }

    private fun createSafeBox(
        fileName: String = this.fileName,
        ioDispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
        recoveryBlobStore: SafeBoxRecoveryBlobStore =
            SafeBoxRecoveryBlobStore.getOrCreate(context),
        cipherProviders: Pair<CipherProvider, CipherProvider> =
            SafeBoxCryptoFactory.createChaCha20Providers(context, fileName),
        failureListener: SafeBox.FailureListener? = null,
    ): SafeBox {
        val (keyCipherProvider, valueCipherProvider) = cipherProviders
        val engine = SafeBoxEngine.create(
            context,
            fileName,
            keyCipherProvider,
            valueCipherProvider,
            ioDispatcher,
            recoveryBlobStore,
            failureListener,
        )
        engines[fileName] = engine
        return SafeBox.createInternal(fileName, engine)
    }

    private suspend fun withForceFailureStore(block: suspend (ForceFailureStore) -> Unit) {
        val fixture = ForceFailureStore()
        try {
            block(fixture)
        } finally {
            fixture.store.closeWhenIdle()
            fixture.file.delete()
        }
    }

    private inner class ForceFailureStore {

        val file = File.createTempFile("safebox-engine-recovery-", ".bin", context.cacheDir)

        var failForce = false

        var forceAttempts = 0
            private set

        val store = SafeBoxRecoveryBlobStore.create(file) {
            forceAttempts++
            if (failForce) {
                throw IOException("Injected recovery force failure")
            }
        }
    }

    private class FailureRecorder : SafeBox.FailureListener {

        private val failures = LinkedBlockingQueue<Pair<Throwable, String>>()

        override fun onFailure(error: Throwable, trace: String) {
            failures.add(error to trace)
        }

        fun awaitFailure(): Pair<Throwable, String> =
            checkNotNull(failures.poll(5, TimeUnit.SECONDS)) { "No failure was delivered" }

        fun assertNoFailure() {
            assertNull(
                failures.poll(200, TimeUnit.MILLISECONDS),
                "Unexpected failure was delivered",
            )
        }
    }

    private class FaultyCipherProvider : CipherProvider {

        var encryptFailure: Exception? = null

        var decryptFailure: Exception? = null

        var unreadableKey: String? = null

        var freshAuthenticationFailures = false

        var encryptCalls = 0
            private set

        override fun encrypt(plaintext: ByteArray): ByteArray {
            encryptCalls++
            encryptFailure?.let { throw it }
            return plaintext
        }

        override fun decrypt(ciphertext: ByteArray): ByteArray {
            if (freshAuthenticationFailures) {
                throw AEADBadTagException("Fresh authentication failure")
            }
            decryptFailure?.let { throw it }
            if (unreadableKey != null && ciphertext.contentEquals(unreadableKey!!.toByteArray())) {
                throw AEADBadTagException("Injected key authentication failure")
            }
            return ciphertext
        }
    }

    private class VersionedKeyCipherProvider : CipherProvider {

        var version = "old"

        var encryptCalls = 0
            private set

        override fun encrypt(plaintext: ByteArray): ByteArray {
            encryptCalls++
            return version.toByteArray() + byteArrayOf(':'.code.toByte()) + plaintext
        }

        override fun decrypt(ciphertext: ByteArray): ByteArray =
            ciphertext.copyOfRange(ciphertext.indexOf(':'.code.toByte()) + 1, ciphertext.size)
    }

    private companion object {

        const val RECOVERY_KEY = "recovery-key"

        const val FIRST_FILLER_KEY = "first-filler"

        const val SECOND_FILLER_KEY = "second-filler"

        // Two values exceed one mapped page.
        val STALE_VALUE = "stale-".repeat(100_000)

        const val NEWER_VALUE = "newer-value"
    }
}
