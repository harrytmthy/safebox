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

package com.harrytmthy.safebox.diagnostics

import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.harrytmthy.safebox.SafeBox.Action
import com.harrytmthy.safebox.engine.SafeBoxEngine.EncryptedAction
import com.harrytmthy.safebox.extensions.toBytes
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class FailureNotifierTest {

    @Test
    fun notifyBatch_withoutListener_shouldKeepExistingConsoleMessage() {
        val marker = UUID.randomUUID().toString()
        val notifier = FailureNotifier("preferences", null)

        notifier.notifyBatch(
            error = IOException(marker),
            kind = FailureKind.PRIMARY_WRITE,
            operation = FailureOperation.WRITE,
            actions = emptyMap(),
            cleared = false,
        )

        val logs = readLogs()
        assertTrue(logs.contains("Failed to commit changes."))
        assertEquals(1, logs.split(marker).size - 1)
        assertFalse(logs.contains("Original failure:\njava.io.IOException: $marker"))
    }

    @Test
    fun notifyRemoval_withoutListener_shouldLogConfirmedRemoval() {
        val marker = UUID.randomUUID().toString()
        val fileName = "preferences-$marker"
        val cause = AEADBadTagException("unreadable-$marker")
        val notifier = FailureNotifier(fileName, null)

        notifier.notifyRemoval(cause, 3)

        val logs = readLogs()
        assertTrue(logs.contains("SafeBox \"$fileName\" cleanup: removed 3 unreadable records"))
        assertTrue(logs.contains("AEADBadTagException: unreadable-$marker"))
        assertEquals(1, logs.split("unreadable-$marker").size - 1)
    }

    @Test
    fun notifyBatch_whenListenerSucceeds_shouldNotAlsoLogFailure() {
        val marker = UUID.randomUUID().toString()
        val delivered = CountDownLatch(1)
        val notifier = FailureNotifier("preferences") { _, _ -> delivered.countDown() }

        notifier.notifyBatch(
            error = IOException(marker),
            kind = FailureKind.PRIMARY_WRITE,
            operation = FailureOperation.WRITE,
            actions = emptyMap(),
            cleared = false,
        )

        assertTrue(delivered.await(5, TimeUnit.SECONDS))
        assertFalse(readLogs().contains(marker))
    }

    @Test
    fun notifyBatch_whenListenerThrows_shouldLogOriginalIncidentAndDeliveryFailureTogether() {
        val original = IOException("original-${UUID.randomUUID()}")
        val listenerError = IllegalStateException("listener-${UUID.randomUUID()}")
        val drained = CountDownLatch(1)
        val notifier = FailureNotifier("preferences") { error, _ ->
            if (error === original) {
                throw listenerError
            }
            drained.countDown()
        }

        notifier.notifyBatch(
            original,
            FailureKind.PRIMARY_WRITE,
            FailureOperation.WRITE,
            linkedMapOf(
                "token" to EncryptedAction("token", Action.Remove, "token".toBytes(), null),
            ),
            false,
        )
        // The next callback runs only after the failed delivery has finished logging.
        notifier.notify(
            error = IOException("next"),
            kind = FailureKind.PRIMARY_LOAD,
            operation = FailureOperation.INITIAL_LOAD,
        )
        assertTrue(drained.await(5, TimeUnit.SECONDS))

        val logs = readLogs()
        assertTrue(
            logs.contains(
                "Failure listener threw while handling:\n" +
                    "SafeBox \"preferences\" write: primary write failed\n  batch: remove token\n" +
                    "Original failure:\njava.io.IOException: ${original.message}",
            ),
        )
        assertTrue(
            logs.contains(
                "Listener failure:\njava.lang.IllegalStateException: " +
                    listenerError.message,
            ),
        )
        assertEquals(1, logs.split(original.message!!).size - 1)
        assertEquals(1, logs.split(listenerError.message!!).size - 1)
    }

    @Test
    fun notifyBatch_whenQueueIsFull_shouldLogOverflowWithoutChangingAcceptedNotifications() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = LinkedBlockingQueue<Pair<Throwable, String>>()
        val first = IOException("first")
        val notifier = FailureNotifier("preferences") { error, trace ->
            if (error === first) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            delivered.add(error to trace)
        }
        val queued = List(16) { IOException("queued-$it") }
        val overflow = IOException("overflow-${UUID.randomUUID()}")
        notifier.notify(first, FailureKind.PRIMARY_LOAD, FailureOperation.INITIAL_LOAD)
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            for (error in queued) {
                notifier.notify(error, FailureKind.PRIMARY_LOAD, FailureOperation.INITIAL_LOAD)
            }

            notifier.notifyBatch(
                overflow,
                FailureKind.PRIMARY_WRITE,
                FailureOperation.WRITE,
                linkedMapOf(
                    "token" to EncryptedAction("token", Action.Remove, "token".toBytes(), null),
                ),
                false,
            )

            val logs = readLogs()
            assertTrue(
                logs.contains(
                    "Failure listener queue full. Logging this failure instead:\n" +
                        "SafeBox \"preferences\" write: primary write failed\n" +
                        "  batch: remove token\n" +
                        "java.io.IOException: ${overflow.message}",
                ),
            )
            assertEquals(1, logs.split(overflow.message!!).size - 1)
        } finally {
            release.countDown()
        }

        for (error in listOf(first) + queued) {
            val failure = delivered.poll(5, TimeUnit.SECONDS)
            assertSame(error, failure?.first)
            assertEquals(
                expected = "SafeBox \"preferences\" initial load: failed to load primary storage",
                actual = failure?.second,
            )
        }
        val next = IOException("after draining")
        notifier.notify(next, FailureKind.PRIMARY_LOAD, FailureOperation.INITIAL_LOAD)
        assertSame(next, delivered.poll(5, TimeUnit.SECONDS)?.first)
    }

    private fun readLogs(): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("logcat -d -v raw --pid=${Process.myPid()} -s SafeBox:E")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader()
            .use { it.readText() }
    }
}
