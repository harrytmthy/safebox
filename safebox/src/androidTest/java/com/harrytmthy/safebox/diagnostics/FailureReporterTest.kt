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
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class FailureReporterTest {

    @Test
    fun reportBatch_withoutListener_shouldKeepExistingConsoleMessage() {
        val marker = UUID.randomUUID().toString()
        val reporter = FailureReporter("preferences", null)

        reporter.reportBatch(IOException(marker), FailureKind.WRITE, emptyMap(), false)

        val logs = readLogs()
        assertTrue(logs.contains("Failed to commit changes."))
        assertEquals(1, logs.split(marker).size - 1)
        assertFalse(logs.contains("Original failure:\njava.io.IOException: $marker"))
    }

    @Test
    fun reportBatch_whenListenerSucceeds_shouldNotAlsoLogFailure() {
        val marker = UUID.randomUUID().toString()
        val delivered = CountDownLatch(1)
        val reporter = FailureReporter("preferences") { _, _ -> delivered.countDown() }

        reporter.reportBatch(IOException(marker), FailureKind.WRITE, emptyMap(), false)

        assertTrue(delivered.await(5, TimeUnit.SECONDS))
        assertFalse(readLogs().contains(marker))
    }

    @Test
    fun reportBatch_whenListenerThrows_shouldLogOriginalIncidentAndDeliveryFailureTogether() {
        val original = IOException("original-${UUID.randomUUID()}")
        val listenerError = IllegalStateException("listener-${UUID.randomUUID()}")
        val drained = CountDownLatch(1)
        val reporter = FailureReporter("preferences") { error, _ ->
            if (error === original) {
                throw listenerError
            }
            drained.countDown()
        }

        reporter.reportBatch(
            original,
            FailureKind.WRITE,
            linkedMapOf("token" to Action.Remove),
            false,
        )
        // The next callback runs only after the failed delivery has finished logging.
        reporter.report(IOException("next"), FailureKind.LOAD)
        assertTrue(drained.await(5, TimeUnit.SECONDS))

        val logs = readLogs()
        assertTrue(
            logs.contains(
                "Failure listener threw while reporting:\n" +
                    "SafeBox \"preferences\" failed to write\n  batch: remove token\n" +
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
    fun reportBatch_whenQueueIsFull_shouldLogOverflowWithoutChangingAcceptedReports() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = LinkedBlockingQueue<Pair<Throwable, String>>()
        val first = IOException("first")
        val reporter = FailureReporter("preferences") { error, trace ->
            if (error === first) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            delivered.add(error to trace)
        }
        val queued = List(16) { IOException("queued-$it") }
        val overflow = IOException("overflow-${UUID.randomUUID()}")
        reporter.report(first, FailureKind.LOAD)
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            for (error in queued) reporter.report(error, FailureKind.LOAD)

            reporter.reportBatch(
                overflow,
                FailureKind.WRITE,
                linkedMapOf("token" to Action.Remove),
                false,
            )

            val logs = readLogs()
            assertTrue(
                logs.contains(
                    "Failure listener queue full. Reporting this failure to logcat instead:\n" +
                        "SafeBox \"preferences\" failed to write\n  batch: remove token\n" +
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
            assertEquals("SafeBox \"preferences\" failed to load stored entries", failure?.second)
        }
        val next = IOException("after draining")
        reporter.report(next, FailureKind.LOAD)
        assertSame(next, delivered.poll(5, TimeUnit.SECONDS)?.first)
    }

    private fun readLogs(): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("logcat -d -v raw --pid=${Process.myPid()} -s SafeBox:E")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader()
            .use { it.readText() }
    }
}
