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

package com.harrytmthy.safebox.demo.data

import android.content.SharedPreferences
import androidx.core.content.edit
import com.harrytmthy.safebox.demo.domain.PlaygroundRepository
import com.harrytmthy.safebox.demo.ui.enums.Action
import com.harrytmthy.safebox.demo.ui.model.KeyValueEntry
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaygroundRepositoryImpl @Inject constructor(
    private val prefs: SharedPreferences,
) : PlaygroundRepository {

    override fun contains(key: String): Boolean =
        prefs.contains(key)

    override fun getString(key: String): String? =
        prefs.getString(key, null)

    override fun saveEntries(
        entries: List<KeyValueEntry>,
        shouldClear: Boolean,
        shouldCommit: Boolean,
    ) {
        prefs.edit(commit = shouldCommit) {
            if (shouldClear) {
                clear()
            }
            for (entry in entries) {
                if (entry.action == Action.PUT) {
                    putString(entry.key, entry.value)
                } else {
                    remove(entry.key)
                }
            }
        }
    }
}
