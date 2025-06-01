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

import com.harrytmthy.safebox.state.SafeBoxGlobalStateObserver.updateState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Public observer interface for monitoring SafeBox state changes.
 *
 * Allows clients to observe SafeBox's lifecycle state transitions for a given file name,
 * enabling safer orchestration in multi-screen or asynchronous apps.
 */
public object SafeBoxGlobalStateObserver {

    private val stateHolder = ConcurrentHashMap<String, SafeBoxState>()

    private val listeners = ConcurrentHashMap<String, CopyOnWriteArraySet<SafeBoxStateListener>>()

    /**
     * Returns the most recently known state of the SafeBox associated with the given file name.
     *
     * This value reflects the latest emitted state via [updateState], even if no active listener
     * was registered at the time of the change.
     *
     * @param fileName The file name being observed.
     * @return The last known [SafeBoxState], or `null` if the file name has never been registered.
     */
    @JvmStatic
    public fun getCurrentState(fileName: String): SafeBoxState? =
        stateHolder[fileName]

    /**
     * Adds a listener for the given SafeBox file name. The listener will immediately receive
     * the current state (if any), followed by all future updates.
     *
     * @param fileName The name of the SafeBox file to observe.
     * @param listener The listener to be notified of state changes.
     */
    @JvmStatic
    public fun addListener(fileName: String, listener: SafeBoxStateListener) {
        listeners.getOrPut(fileName, defaultValue = { CopyOnWriteArraySet() }).add(listener)
        stateHolder[fileName]?.let(listener::onStateChanged)
    }

    /**
     * Removes a previously registered listener for a specific file.
     *
     * @param fileName The file name being observed.
     * @param listener The listener to remove.
     */
    @JvmStatic
    public fun removeListener(fileName: String, listener: SafeBoxStateListener) {
        listeners[fileName]?.remove(listener)
    }

    /**
     * Emits a new state for the given file name, updating internal records
     * and notifying all registered listeners for that file.
     *
     * This method is called internally by SafeBox and SafeBoxBlobFileRegistry
     * to reflect changes in the instance's lifecycle.
     *
     * @param fileName The file name whose state has changed.
     * @param newState The new [SafeBoxState] to emit.
     */
    internal fun updateState(fileName: String, newState: SafeBoxState) {
        stateHolder[fileName] = newState
        listeners[fileName]?.forEach { it.onStateChanged(newState) }
    }
}
