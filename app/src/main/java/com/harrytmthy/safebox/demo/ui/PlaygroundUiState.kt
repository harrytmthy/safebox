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

package com.harrytmthy.safebox.demo.ui

import androidx.lifecycle.SavedStateHandle
import com.harrytmthy.safebox.demo.ui.model.ClearedEntry
import com.harrytmthy.safebox.demo.ui.model.Entry
import com.harrytmthy.safebox.demo.ui.model.KeyValueEntry

data class PlaygroundUiState(
    val currentKey: String,
    val currentValue: String,
    val shouldCommit: Boolean,
    val stagedEntries: List<Entry>,
) {

    companion object {

        fun create(savedStateHandle: SavedStateHandle): PlaygroundUiState =
            PlaygroundUiState(
                currentKey = savedStateHandle.get<String>("currentKey").orEmpty(),
                currentValue = savedStateHandle.get<String>("currentValue").orEmpty(),
                shouldCommit = savedStateHandle.get<Boolean>("shouldCommit") ?: false,
                stagedEntries = buildList {
                    if (savedStateHandle.get<Boolean>("shouldClear") == true) {
                        add(ClearedEntry)
                    }
                    savedStateHandle.get<ArrayList<KeyValueEntry>>("stagedEntries")?.let(::addAll)
                },
            )
    }
}
