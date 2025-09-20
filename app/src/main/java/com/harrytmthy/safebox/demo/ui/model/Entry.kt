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

package com.harrytmthy.safebox.demo.ui.model

import android.os.Parcelable
import com.harrytmthy.safebox.demo.ui.enums.Action
import kotlinx.parcelize.Parcelize

sealed interface Entry {
    val id: String
}

@Parcelize
data class KeyValueEntry(
    override val id: String,
    val key: String,
    val value: String,
    val action: Action,
) : Entry, Parcelable

data object ClearedEntry : Entry {
    override val id: String = "ClearedEntry"
}
