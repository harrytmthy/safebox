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
import com.harrytmthy.safebox.demo.R
import com.harrytmthy.safebox.demo.core.ResourcesProvider
import com.harrytmthy.safebox.demo.core.TestDispatcherProvider
import com.harrytmthy.safebox.demo.domain.PlaygroundRepository
import com.harrytmthy.safebox.demo.ui.PlaygroundViewModel.Event.ShowSnackBar
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaygroundViewModelTest {

    private val dispatchersProvider = TestDispatcherProvider()

    private val playgroundRepository = object : PlaygroundRepository {

        val appliedEntries = LinkedHashMap<String, String>()

        val committedEntries = LinkedHashMap<String, String>()

        override fun contains(key: String): Boolean =
            appliedEntries.contains(key) || committedEntries.contains(key)

        override fun getString(key: String): String? =
            committedEntries[key] ?: appliedEntries[key]

        override fun saveEntries(entries: Map<String, String>, shouldCommit: Boolean) {
            if (!shouldCommit) {
                appliedEntries += entries
            } else {
                committedEntries += entries
            }
        }
    }

    private val resourcesProvider = object : ResourcesProvider {

        override fun getString(resId: Int): String = resId.toString()

        override fun getString(resId: Int, vararg formatArgs: Any): String = resId.toString()
    }

    @Test
    fun apply() {
        val initialState = mapOf("currentKey" to "key", "currentValue" to "test")
        val viewModel = PlaygroundViewModel(
            savedStateHandle = SavedStateHandle(initialState),
            dispatchersProvider = TestDispatcherProvider(),
            playgroundRepository = playgroundRepository,
            resourcesProvider = resourcesProvider,
        )

        viewModel.applyOrCommit()

        assertEquals("test", playgroundRepository.appliedEntries["key"])
    }

    @Test
    fun commit() {
        val initialState = mapOf("currentKey" to "key", "currentValue" to "test")
        val viewModel = PlaygroundViewModel(
            savedStateHandle = SavedStateHandle(initialState),
            dispatchersProvider = TestDispatcherProvider(),
            playgroundRepository = playgroundRepository,
            resourcesProvider = resourcesProvider,
        )
        viewModel.toggleCommit()

        viewModel.applyOrCommit()

        assertEquals("test", playgroundRepository.committedEntries["key"])
    }

    @Test
    fun getString() = runTest {
        val initialState = mapOf("currentKey" to "key", "currentValue" to "test")
        val viewModel = PlaygroundViewModel(
            savedStateHandle = SavedStateHandle(initialState),
            dispatchersProvider = TestDispatcherProvider(),
            playgroundRepository = playgroundRepository,
            resourcesProvider = resourcesProvider,
        )
        viewModel.applyOrCommit()
        viewModel.updateCurrentKey("key")
        val result = ArrayList<PlaygroundUiState>()
        val job = launch(dispatchersProvider.io) {
            viewModel.state.drop(1).toList(result)
        }

        viewModel.getString()

        assertEquals("test", result.single().currentValue)
        job.cancel()
    }

    @Test
    fun getString_withEmptyKey() = runTest {
        val viewModel = PlaygroundViewModel(
            savedStateHandle = SavedStateHandle(),
            dispatchersProvider = TestDispatcherProvider(),
            playgroundRepository = playgroundRepository,
            resourcesProvider = resourcesProvider,
        )
        val events = ArrayList<PlaygroundViewModel.Event>()
        val job = launch(dispatchersProvider.io) {
            viewModel.event.toList(events)
        }

        viewModel.getString()

        val expected = ShowSnackBar(R.string.playground_error_missing_key.toString())
        assertEquals(expected, events.single())
        job.cancel()
    }
}
