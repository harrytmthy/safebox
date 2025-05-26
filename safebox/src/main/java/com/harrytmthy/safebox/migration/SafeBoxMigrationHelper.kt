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

package com.harrytmthy.safebox.migration

import android.annotation.SuppressLint
import android.content.SharedPreferences
import com.harrytmthy.safebox.SafeBox

public object SafeBoxMigrationHelper {

    /**
     * Utility for migrating existing data from one [SharedPreferences] instance to another.
     *
     * Designed to help migrate data from any [SharedPreferences] to [SafeBox]
     * without breaking existing key-value pairs or requiring major refactors.
     *
     * @param from The source [SharedPreferences] to read from.
     * @param to The destination [SharedPreferences] to write to.
     * @param commit Whether to commit immediately. If false, uses `apply()`.
     */
    @SuppressLint("ApplySharedPref")
    @Suppress("UNCHECKED_CAST")
    @JvmOverloads
    @JvmStatic
    public fun migrate(
        from: SharedPreferences,
        to: SharedPreferences,
        commit: Boolean = true,
    ) {
        to.edit().apply {
            for ((key, value) in from.all) {
                when (value) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Float -> putFloat(key, value)
                    is Long -> putLong(key, value)
                    is Set<*> -> putStringSet(key, value as? Set<String> ?: continue)
                    else -> continue
                }
            }
            if (commit) {
                commit()
            } else {
                apply()
            }
        }
    }
}
