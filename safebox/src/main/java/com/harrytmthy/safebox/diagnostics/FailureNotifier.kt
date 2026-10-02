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
import com.harrytmthy.safebox.SafeBox.Action
import com.harrytmthy.safebox.engine.SafeBoxEngine.EncryptedAction
import com.harrytmthy.safebox.extensions.safeBoxScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.crypto.AEADBadTagException

internal class FailureNotifier(
    private val fileName: String,
    private val listener: SafeBox.FailureListener?,
) {

    private val pendingFailures = listener?.let { ArrayDeque<Pair<Exception, String>>() }

    private var deliveringFailures = false

    fun notify(
        error: Exception,
        kind: FailureKind,
        operation: FailureOperation,
        action: Map.Entry<String, Action>? = null,
    ) {
        if (listener == null) {
            logFailure(error, kind)
            return
        }
        if (error is CancellationException) {
            return
        }
        val trace = buildString {
            appendHeader(operation, kind.description)
            if (action != null) {
                append("\n  ").append(action.value.describe(action.key))
            }
        }
        enqueue(error, trace)
    }

    fun notifyBatch(
        error: Exception,
        kind: FailureKind,
        operation: FailureOperation,
        actions: Map<String, EncryptedAction>?,
        cleared: Boolean,
    ) {
        if (listener == null) {
            logFailure(error, kind, batch = true)
            return
        }
        if (error is CancellationException) {
            return
        }
        val trace = buildString {
            appendHeader(operation, kind.description)
            if (actions != null) {
                append("\n  batch: ")
                if (cleared) {
                    append("clear")
                }
                for ((index, entry) in actions.values.withIndex()) {
                    if (index == MAX_TRACED_ACTIONS) {
                        break
                    }
                    if (cleared || index > 0) {
                        append(", ")
                    }
                    append(entry.value.describe(entry.key))
                }
                if (actions.size > MAX_TRACED_ACTIONS) {
                    append(", ").append(actions.size - MAX_TRACED_ACTIONS)
                        .append(" actions omitted")
                }
            }
        }
        enqueue(error, trace)
    }

    /**
     * Reports a completed cleanup using one of the authentication failures that caused it.
     * The count includes only records whose deletion and required flushes succeeded.
     * Without a listener, the cleanup report is logged.
     */
    fun notifyRemoval(cause: AEADBadTagException, count: Int) {
        if (count == 0) {
            return
        }
        val noun = if (count == 1) {
            "record"
        } else {
            "records"
        }
        val trace = buildString {
            appendHeader(FailureOperation.CLEANUP, "removed $count unreadable $noun")
        }
        if (listener == null) {
            Log.e("SafeBox", trace, cause)
        } else {
            enqueue(cause, trace)
        }
    }

    private fun StringBuilder.appendHeader(operation: FailureOperation, tag: String) {
        val name = fileName.take(128)
            .replace("\r", "\\r")
            .replace("\n", "\\n")
        append("SafeBox \"").append(name).append("\" ")
            .append(operation.description).append(": ").append(tag)
    }

    private fun logFailure(error: Exception, kind: FailureKind, batch: Boolean = false) {
        val message = when {
            kind == FailureKind.PRIMARY_FLUSH -> "Failed to flush pending changes."
            batch -> "Failed to commit changes."
            (kind == FailureKind.DECRYPT_KEY || kind == FailureKind.DECRYPT_VALUE) &&
                error is AEADBadTagException ->
                "Decrypt failed due to AEADBadTagException."
            else -> return
        }
        Log.e("SafeBox", message, error)
    }

    private fun Action.describe(key: String): String {
        val boundedKey = key.take(MAX_TRACED_KEY_LENGTH)
            .replace("\r", "\\r")
            .replace("\n", "\\n")
        val suffix = if (key.length > MAX_TRACED_KEY_LENGTH) {
            "..."
        } else {
            ""
        }
        val operation = if (this is Action.Put) {
            "put"
        } else {
            "remove"
        }
        return "$operation $boundedKey$suffix"
    }

    private fun enqueue(error: Exception, trace: String) {
        val pendingFailures = pendingFailures ?: return
        val listener = listener ?: return
        val queueFull = synchronized(pendingFailures) {
            if (pendingFailures.size == MAX_PENDING_FAILURES) {
                true
            } else {
                pendingFailures.addLast(error to trace)
                if (deliveringFailures) {
                    return
                }
                deliveringFailures = true
                false
            }
        }
        if (queueFull) {
            Log.e(
                "SafeBox",
                "Failure listener queue full. Logging this failure instead:\n$trace",
                error,
            )
            return
        }

        // The storage dispatcher may run inline or be blocked by a listener's commit().
        safeBoxScope.launch(Dispatchers.IO) {
            while (true) {
                val (nextError, nextTrace) = synchronized(pendingFailures) {
                    val next = pendingFailures.removeFirstOrNull()
                    if (next == null) {
                        deliveringFailures = false
                        return@launch
                    }
                    next
                }
                try {
                    listener.onFailure(nextError, nextTrace)
                } catch (e: Exception) {
                    val fallback = buildString {
                        append("Failure listener threw while handling:\n").append(nextTrace)
                        append("\nOriginal failure:\n").append(Log.getStackTraceString(nextError))
                        append("\nListener failure:")
                    }
                    Log.e("SafeBox", fallback, e)
                }
            }
        }
    }

    private companion object {
        const val MAX_TRACED_KEY_LENGTH = 128
        const val MAX_TRACED_ACTIONS = 32
        const val MAX_PENDING_FAILURES = 16
    }
}
