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

/**
 * Represents the current lifecycle state of a [SafeBox] instance.
 *
 * These states are exposed through [SafeBoxStateListener] and help consumers track
 * SafeBox activity, especially during asynchronous operations.
 *
 * @see SafeBoxStateListener
 */
public enum class SafeBoxState {

    /**
     * Indicates that SafeBox has been successfully created and currently loading persisted data
     * from disk into memory.
     *
     * During this state, SafeBox is not yet ready to serve read or write operations.
     * Any read/write call will be suspended until the state transitions to [IDLE].
     */
    STARTING,

    /**
     * Indicates that SafeBox is ready and currently idle, with no active write operations.
     * This is the default state after initialization completes and between writes.
     */
    IDLE,

    /**
     * Indicates that SafeBox is currently writing data to disk.
     * Avoid closing or deleting the SafeBox during this time.
     */
    WRITING,

    /**
     * Indicates that SafeBox has been closed and is no longer usable.
     * To access the same file again, a new SafeBox instance must be created.
     */
    CLOSED,
}
