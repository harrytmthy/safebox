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
 * **Deprecated:** SafeBox is designed to be a drop-in replacement for EncryptedSharedPreferences.
 * State updates goes out-of-scope as SafeBox can no longer be closed.
 *
 * Lifecycle state of a [SafeBox] instance.
 *
 * Emitted to [SafeBoxStateListener] for visibility during async work.
 *
 * @see SafeBoxStateListener
 */
@Deprecated(message = "SafeBoxState is deprecated and will be removed in v1.4.")
public enum class SafeBoxState {

    /**
     * Indicates SafeBox instance is created and is loading persisted entries from disk.
     *
     * Reads return the default value until the initial load completes.
     *
     * Writes update the in-memory map immediately. Disk persistence follows the initial load.
     */
    STARTING,

    /**
     * Ready and idle. No active persistence is running.
     */
    IDLE,

    /**
     * One or more writes are being persisted to disk.
     *
     * Reads reflect the latest in-memory values.
     *
     * Writes update the in-memory map immediately. Disk persistence follows the current batch.
     */
    WRITING,
}
