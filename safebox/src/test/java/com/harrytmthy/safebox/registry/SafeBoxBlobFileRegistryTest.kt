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

import kotlin.test.Test
import kotlin.test.assertFailsWith

class SafeBoxBlobFileRegistryTest {

    @Test
    fun `register and unregister should succeed`() {
        val fileName = "test_file"

        SafeBoxBlobFileRegistry.register(fileName)
        SafeBoxBlobFileRegistry.unregister(fileName)
        SafeBoxBlobFileRegistry.register(fileName)
    }

    @Test
    fun `register twice should throw IllegalStateException`() {
        val fileName = "duplicate_file"

        SafeBoxBlobFileRegistry.register(fileName)

        assertFailsWith<IllegalStateException> { SafeBoxBlobFileRegistry.register(fileName) }
    }

    @Test
    fun `unregister non-registered file should succeed`() {
        val fileName = "nonexistent_file"

        SafeBoxBlobFileRegistry.unregister(fileName)
    }
}
