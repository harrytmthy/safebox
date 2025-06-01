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

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SafeBoxGlobalStateObserverTest {

    private val fileName = "test_box"

    private lateinit var receivedStates: MutableList<SafeBoxState>

    private lateinit var listener: SafeBoxStateListener

    @BeforeTest
    fun setUp() {
        receivedStates = mutableListOf()
        listener = SafeBoxStateListener { receivedStates.add(it) }
    }

    @AfterTest
    fun tearDown() {
        SafeBoxGlobalStateObserver.removeListener(fileName, listener)
        receivedStates.clear()
    }

    @Test
    fun addListener_shouldImmediatelyReceiveCurrentState() {
        SafeBoxGlobalStateObserver.updateState(fileName, SafeBoxState.WRITING)
        SafeBoxGlobalStateObserver.addListener(fileName, listener)
        assertEquals(listOf(SafeBoxState.WRITING), receivedStates)
    }

    @Test
    fun updateState_shouldNotifyAllListeners() {
        SafeBoxGlobalStateObserver.addListener(fileName, listener)
        SafeBoxGlobalStateObserver.updateState(fileName, SafeBoxState.WRITING)
        SafeBoxGlobalStateObserver.updateState(fileName, SafeBoxState.IDLE)

        assertEquals(listOf(SafeBoxState.WRITING, SafeBoxState.IDLE), receivedStates)
    }

    @Test
    fun getCurrentState_shouldReturnLatestState() {
        SafeBoxGlobalStateObserver.updateState(fileName, SafeBoxState.WRITING)
        val result = SafeBoxGlobalStateObserver.getCurrentState(fileName)
        assertEquals(SafeBoxState.WRITING, result)
    }

    @Test
    fun getCurrentState_shouldReturnNullForUnknownFile() {
        val result = SafeBoxGlobalStateObserver.getCurrentState("unknown_file")
        assertNull(result)
    }
}
