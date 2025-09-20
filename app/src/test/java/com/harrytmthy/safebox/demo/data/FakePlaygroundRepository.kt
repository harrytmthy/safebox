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

import com.harrytmthy.safebox.demo.domain.PlaygroundRepository
import com.harrytmthy.safebox.demo.ui.enums.Action
import com.harrytmthy.safebox.demo.ui.model.KeyValueEntry

class FakePlaygroundRepository : PlaygroundRepository {

    val appliedEntries = LinkedHashMap<String, String>()

    val committedEntries = LinkedHashMap<String, String>()

    var exception: Exception? = null

    override fun contains(key: String): Boolean {
        exception?.let { throw it }
        return appliedEntries.contains(key) || committedEntries.contains(key)
    }

    override fun getString(key: String): String? {
        exception?.let { throw it }
        return committedEntries[key] ?: appliedEntries[key]
    }

    override fun saveEntries(
        entries: List<KeyValueEntry>,
        shouldClear: Boolean,
        shouldCommit: Boolean,
    ) {
        exception?.let { throw it }
        if (shouldClear) {
            appliedEntries.clear()
            committedEntries.clear()
        }
        for (entry in entries) {
            when (entry.action) {
                Action.PUT -> {
                    if (!shouldCommit) {
                        appliedEntries.put(entry.key, entry.value)
                    } else {
                        committedEntries.put(entry.key, entry.value)
                    }
                }
                Action.REMOVE -> {
                    if (!shouldCommit) {
                        appliedEntries.remove(entry.key)
                    } else {
                        committedEntries.remove(entry.key)
                    }
                }
            }
        }
    }
}
