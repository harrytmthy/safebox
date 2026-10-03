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
import com.harrytmthy.safebox.SafeBox
import com.harrytmthy.safebox.SafeBox.FailureOperation
import kotlinx.coroutines.CancellationException
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class FailureNotifierTest {

    @Test
    fun failureToString_shouldBoundAndEscapeFileNameWithoutIncludingExceptionDetails() {
        val fileName = "preferences\r\n" + "x".repeat(150)
        val error = IOException("Private exception details")
        val failure = SafeBox.Failure(fileName, FailureOperation.RECOVERY_REPLAY, error)

        val description = failure.toString()

        assertEquals(
            "SafeBox \"preferences\\r\\n${"x".repeat(115)}\" failed during recovery replay",
            description,
        )
        assertFalse(description.contains(error.message!!))
        assertEquals(fileName, failure.fileName)
        assertSame(error, failure.error)
    }

    @Test
    fun notify_shouldDeliverOriginalExceptionFileNameAndOperation() {
        val delivered = LinkedBlockingQueue<SafeBox.Failure>()
        val notifier = FailureNotifier("preferences") { delivered.add(it) }
        val error = IOException("original")

        notifier.notify(error, FailureOperation.INITIAL_LOAD)

        val failure = checkNotNull(delivered.poll(5, TimeUnit.SECONDS))
        assertSame(error, failure.error)
        assertEquals("preferences", failure.fileName)
        assertEquals(FailureOperation.INITIAL_LOAD, failure.operation)
        assertNull(delivered.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun notify_withoutListener_shouldLogTheOperationAndOriginalException() {
        val marker = UUID.randomUUID().toString()
        val notifier = FailureNotifier("preferences", null)

        notifier.notify(IOException(marker), FailureOperation.WRITE)

        val logs = readLogs()
        assertTrue(logs.contains("SafeBox \"preferences\" failed during write"))
        assertEquals(1, logs.split(marker).size - 1)
    }

    @Test
    fun notify_shouldIgnoreCancellationWithAndWithoutListener() {
        val marker = UUID.randomUUID().toString()
        val delivered = LinkedBlockingQueue<SafeBox.Failure>()
        val cancellation = CancellationException(marker)

        FailureNotifier("preferences", null).notify(cancellation, FailureOperation.WRITE)
        FailureNotifier("preferences") { delivered.add(it) }
            .notify(cancellation, FailureOperation.WRITE)

        assertNull(delivered.poll(200, TimeUnit.MILLISECONDS))
        assertFalse(readLogs().contains(marker))
    }

    @Test
    fun notify_whenListenerSucceeds_shouldNotAlsoLogFailure() {
        val marker = UUID.randomUUID().toString()
        val delivered = CountDownLatch(1)
        val notifier = FailureNotifier("preferences") { delivered.countDown() }

        notifier.notify(IOException(marker), FailureOperation.WRITE)

        assertTrue(delivered.await(5, TimeUnit.SECONDS))
        assertFalse(readLogs().contains(marker))
    }

    @Test
    fun notify_whenListenerThrows_shouldLogBothExceptionsAndContinueDelivery() {
        val original = IOException("original-${UUID.randomUUID()}")
        val listenerError = IllegalStateException("listener-${UUID.randomUUID()}")
        val drained = CountDownLatch(1)
        val notifier = FailureNotifier("preferences") { failure ->
            if (failure.error === original) {
                throw listenerError
            }
            drained.countDown()
        }

        notifier.notify(original, FailureOperation.WRITE)
        notifier.notify(IOException("next"), FailureOperation.INITIAL_LOAD)
        assertTrue(drained.await(5, TimeUnit.SECONDS))

        val logs = readLogs()
        assertTrue(logs.contains("Failure listener threw while handling"))
        assertTrue(logs.contains("Original SafeBox \"preferences\" failed during write"))
        assertEquals(1, logs.split(original.message!!).size - 1)
        assertEquals(1, logs.split(listenerError.message!!).size - 1)
    }

    @Test
    fun notify_whenQueueIsFull_shouldLogOverflowWithoutChangingAcceptedNotifications() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = LinkedBlockingQueue<SafeBox.Failure>()
        val first = IOException("first")
        val notifier = FailureNotifier("preferences") { failure ->
            if (failure.error === first) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            delivered.add(failure)
        }
        val queued = List(16) { IOException("queued-$it") }
        val overflow = IOException("overflow-${UUID.randomUUID()}")
        notifier.notify(first, FailureOperation.INITIAL_LOAD)
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            for (error in queued) {
                notifier.notify(error, FailureOperation.INITIAL_LOAD)
            }
            notifier.notify(overflow, FailureOperation.WRITE)

            val logs = readLogs()
            assertTrue(logs.contains("failure listener queue is full"))
            assertEquals(1, logs.split(overflow.message!!).size - 1)
        } finally {
            release.countDown()
        }

        for (error in listOf(first) + queued) {
            val failure = checkNotNull(delivered.poll(5, TimeUnit.SECONDS))
            assertSame(error, failure.error)
            assertEquals("preferences", failure.fileName)
            assertEquals(FailureOperation.INITIAL_LOAD, failure.operation)
        }
        assertNull(delivered.poll(200, TimeUnit.MILLISECONDS))
        val next = IOException("after draining")
        notifier.notify(next, FailureOperation.READ)
        assertSame(next, delivered.poll(5, TimeUnit.SECONDS)?.error)
    }

    @Test
    fun notify_withoutListener_shouldEscapeAndLimitOnlyTheLoggedFileName() {
        val marker = UUID.randomUUID().toString()
        val fileName = "$marker\r\n" + "x".repeat(150)
        val notifier = FailureNotifier(fileName, null)

        notifier.notify(IOException(marker), FailureOperation.READ)

        val logs = readLogs()
        assertTrue(logs.contains("$marker\\r\\n"))
        assertFalse(logs.contains("x".repeat(150)))
    }

    private fun readLogs(): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("logcat -d -v raw --pid=${Process.myPid()} -s SafeBox:E")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader()
            .use { it.readText() }
    }
}
