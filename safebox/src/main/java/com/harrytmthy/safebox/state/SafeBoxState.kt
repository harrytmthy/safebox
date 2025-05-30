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
     * Indicates that SafeBox is idle and not currently writing to disk.
     * This is the default resting state.
     */
    IDLE,

    /**
     * Indicates that SafeBox is performing a write operation.
     * Avoid closing or deleting the SafeBox during this time.
     */
    WRITING,

    /**
     * Indicates that SafeBox has been closed and is no longer usable.
     * Once closed, a SafeBox instance cannot be reused.
     */
    CLOSED,
}
