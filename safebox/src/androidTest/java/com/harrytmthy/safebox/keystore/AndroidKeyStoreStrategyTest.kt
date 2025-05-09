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

package com.harrytmthy.safebox.keystore

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harrytmthy.safebox.mode.AesMode
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class AndroidKeyStoreStrategyTest {

    @Test
    fun getOrCreateKey_shouldReturnValidAesKey() {
        val strategy = AndroidKeyStoreStrategy.create(AesMode.Gcm(), "TestAlias")
        val key = strategy.getOrCreateKey()
        assertEquals("AES", key.algorithm)
    }

    @Test
    fun getOrCreateKey_shouldReturnTheSameKeyAcrossInvocations() {
        val strategy = AndroidKeyStoreStrategy.create(AesMode.Gcm(), "TestAlias")
        val first = strategy.getOrCreateKey()
        val second = strategy.getOrCreateKey()
        assertEquals(first, second)
    }
}
