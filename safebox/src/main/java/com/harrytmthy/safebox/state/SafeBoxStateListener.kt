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

/**
 * **Deprecated:** SafeBox is designed to be a drop-in replacement for EncryptedSharedPreferences.
 * State updates goes out-of-scope as SafeBox can no longer be closed.
 *
 * A listener interface for observing [SafeBoxState] changes tied to a specific SafeBox file.
 *
 * This is typically used in non-singleton SafeBox use cases (e.g. ViewModel-scoped),
 * where consumers need to track if the instance is starting, writing, or idle.
 *
 * @see SafeBoxState
 */
@Deprecated(message = "SafeBoxStateListener is deprecated and will be removed in v1.4.")
public fun interface SafeBoxStateListener {

    /**
     * Called whenever there is a state update.
     *
     * @param state The latest state of the SafeBox instance.
     */
    fun onStateChanged(state: SafeBoxState)
}
