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

import android.util.Log
import com.harrytmthy.safebox.SafeBox
import com.harrytmthy.safebox.SafeBox.FailureOperation
import com.harrytmthy.safebox.extensions.safeBoxScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class FailureNotifier(
    private val fileName: String,
    private val listener: SafeBox.FailureListener?,
) {

    private val pendingFailures = listener?.let {
        ArrayDeque<SafeBox.Failure>(MAX_PENDING_FAILURES)
    }

    private var deliveringFailures = false

    fun notify(error: Throwable, operation: FailureOperation) {
        if (error is CancellationException) {
            return
        }
        val failure = SafeBox.Failure(fileName, operation, error)
        if (listener == null) {
            Log.e(TAG, failure.toString(), failure.error)
            return
        }
        enqueue(failure)
    }

    private fun enqueue(failure: SafeBox.Failure) {
        val pendingFailures = pendingFailures ?: return
        val listener = listener ?: return
        val queueFull = synchronized(pendingFailures) {
            if (pendingFailures.size == MAX_PENDING_FAILURES) {
                true
            } else {
                pendingFailures.addLast(failure)
                if (deliveringFailures) {
                    return
                }
                deliveringFailures = true
                false
            }
        }
        if (queueFull) {
            Log.e(TAG, "$failure: failure listener queue is full.", failure.error)
            return
        }

        // Delivery must not share a storage dispatcher blocked by a listener's commit().
        safeBoxScope.launch(Dispatchers.IO) {
            while (true) {
                val next = synchronized(pendingFailures) {
                    val next = pendingFailures.removeFirstOrNull()
                    if (next == null) {
                        deliveringFailures = false
                        return@launch
                    }
                    next
                }
                try {
                    listener.onFailure(next)
                } catch (listenerError: Exception) {
                    Log.e(
                        TAG,
                        "Failure listener threw while handling $next",
                        listenerError,
                    )
                    Log.e(TAG, "Original $next", next.error)
                }
            }
        }
    }

    private companion object {
        const val TAG = "SafeBox"
        const val MAX_PENDING_FAILURES = 16
    }
}
