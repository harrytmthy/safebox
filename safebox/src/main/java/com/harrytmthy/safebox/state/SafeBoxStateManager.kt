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

package com.harrytmthy.safebox.state

import com.harrytmthy.safebox.SafeBox
import com.harrytmthy.safebox.extensions.safeBoxScope
import com.harrytmthy.safebox.state.SafeBoxState.IDLE
import com.harrytmthy.safebox.state.SafeBoxState.STARTING
import com.harrytmthy.safebox.state.SafeBoxState.WRITING
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages the lifecycle state of a [SafeBox] instance and coordinates concurrent read/write access.
 *
 * This class emits state changes to both the instance-bound [SafeBoxStateListener] and the global
 * [SafeBoxGlobalStateObserver].
 *
 * Key behaviors:
 * - Tracks concurrent writes using an atomic counter.
 * - Waits for the blob store's initial read before permitting writes.
 * - Guarantees transition to [IDLE] after all writes complete.
 *
 * @param fileName The unique file identifier associated with this SafeBox instance.
 * @param stateListener Optional listener for observing state transitions on this instance.
 * @param ioDispatcher Dispatcher used for coroutine-based I/O tasks.
 */
internal class SafeBoxStateManager(
    private val fileName: String,
    private var stateListener: SafeBoxStateListener?,
    private val ioDispatcher: CoroutineDispatcher,
) {

    private val concurrentWriteCount = AtomicInteger(0)

    private val initialReadCompleted = CompletableDeferred<Unit>()

    fun setStateListener(stateListener: SafeBoxStateListener?) {
        this.stateListener = stateListener
    }

    inline fun launchWithStartingState(crossinline block: suspend () -> Unit) {
        updateState(STARTING)
        safeBoxScope.launch(ioDispatcher) {
            try {
                block()
            } finally {
                initialReadCompleted.complete(Unit)
                if (concurrentWriteCount.get() == 0) {
                    updateState(IDLE)
                }
            }
        }
    }

    inline fun launchCommitWithWritingState(crossinline block: suspend () -> Boolean): Boolean =
        runBlocking {
            withStateTransition(block)
        }

    inline fun launchApplyWithWritingState(crossinline block: suspend () -> Unit) {
        safeBoxScope.launch(ioDispatcher) {
            withStateTransition(block)
        }
    }

    private suspend inline fun <T> withStateTransition(crossinline block: suspend () -> T): T {
        initialReadCompleted.await()
        if (concurrentWriteCount.incrementAndGet() == 1) {
            updateState(WRITING)
        }
        return try {
            block()
        } finally {
            if (concurrentWriteCount.decrementAndGet() == 0) {
                updateState(IDLE)
            }
        }
    }

    private fun updateState(newState: SafeBoxState) {
        stateListener?.onStateChanged(newState)
        SafeBoxGlobalStateObserver.updateState(fileName, newState)
    }
}
