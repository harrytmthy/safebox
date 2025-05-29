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

package com.harrytmthy.safebox.registry

import com.harrytmthy.safebox.SafeBox
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * An internal registry that tracks active SafeBox blob files.
 *
 * Prevents multiple [SafeBox] instances from accessing the same file simultaneously.
 * This ensures thread safety and prevents corruption due to concurrent `FileChannel` access.
 *
 * This registry is internal-only and not intended for external observation.
 * Please use [SafeBoxStateObserver] to listen for state changes.
 */
internal object SafeBoxBlobFileRegistry {

    private val registry: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    /**
     * Registers the given file name as currently in use.
     *
     * @param fileName The file name associated with a SafeBox instance.
     * @throws IllegalStateException if the file is already registered.
     */
    @Throws(IllegalStateException::class)
    fun register(fileName: String) {
        if (registry.contains(fileName)) {
            error("SafeBox with file name '$fileName' is already in use. Please close it first.")
        }
        registry.add(fileName)
    }

    /**
     * Unregisters the given file name, allowing the creation of a new SafeBox instances with
     * an existing file name.
     *
     * @param fileName The file name to unregister.
     */
    fun unregister(fileName: String) {
        registry.remove(fileName)
    }
}
