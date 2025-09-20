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
import com.harrytmthy.safebox.demo.data.FakePlaygroundRepository
import com.harrytmthy.safebox.demo.ui.PlaygroundViewModel.Event
import com.harrytmthy.safebox.demo.ui.PlaygroundViewModel.Event.ShowSnackBar
import com.harrytmthy.safebox.demo.ui.enums.Action
import com.harrytmthy.safebox.demo.ui.model.KeyValueEntry
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.security.GeneralSecurityException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaygroundViewModelTest {

    private val dispatchersProvider = TestDispatcherProvider()

    private val playgroundRepository = FakePlaygroundRepository()

    private val resourcesProvider = object : ResourcesProvider {

        override fun getString(resId: Int): String = resId.toString()

        override fun getString(resId: Int, vararg formatArgs: Any): String = resId.toString()
    }

    @Test
    fun apply() = runTest {
        val viewModel = createViewModel(
            stagedEntries = listOf(KeyValueEntry("", "key", "test", Action.PUT)),
        )

        withObservedStatesAndEvents(viewModel) { states, events ->
            viewModel.applyOrCommit()

            assertEquals("test", playgroundRepository.appliedEntries["key"])
            assertEquals(PlaygroundUiState("", "", false, emptyList()), states.single())
            assertEquals(ShowSnackBar(R.string.playground_success.toString()), events.single())
        }
    }

    @Test
    fun apply_withException() = runTest {
        playgroundRepository.exception = GeneralSecurityException()
        val viewModel = createViewModel(
            stagedEntries = listOf(KeyValueEntry("", "key", "test", Action.PUT)),
        )

        withObservedStatesAndEvents(viewModel) { states, events ->
            viewModel.applyOrCommit()

            assertEquals(ShowSnackBar(R.string.playground_error_commit_failed.toString()), events.single())
            assertTrue(states.isEmpty())
        }
    }

    @Test
    fun apply_withoutStagedEntry() = runTest {
        val viewModel = createViewModel()

        withObservedStatesAndEvents(viewModel) { states, events ->
            viewModel.applyOrCommit()

            assertEquals(
                expected = ShowSnackBar(R.string.playground_error_no_staged_entry.toString()),
                actual = events.single(),
            )
            assertTrue(states.isEmpty())
        }
    }

    @Test
    fun putString_then_commit() = runTest {
        val viewModel = createViewModel(
            currentKey = "key",
            currentValue = "test",
            shouldCommit = true,
        )
        viewModel.putString()
        viewModel.clear()

        withObservedStatesAndEvents(viewModel) { states, events ->
            viewModel.applyOrCommit()

            assertEquals("test", playgroundRepository.committedEntries["key"])
            assertEquals(PlaygroundUiState("", "", true, emptyList()), states.single())
            assertEquals(ShowSnackBar(R.string.playground_success.toString()), events.single())
        }
    }

    @Test
    fun getString() = runTest {
        playgroundRepository.appliedEntries["key"] = "test"
        val viewModel = createViewModel(currentKey = "key")

        withObservedStatesAndEvents(viewModel) { states, events ->
            viewModel.getString()

            assertEquals("test", states.single().currentValue)
            assertTrue(events.isEmpty())
        }
    }

    @Test
    fun getString_withEmptyKey() = runTest {
        val viewModel = PlaygroundViewModel(
            savedStateHandle = SavedStateHandle(),
            dispatchersProvider = TestDispatcherProvider(),
            playgroundRepository = playgroundRepository,
            resourcesProvider = resourcesProvider,
        )

        withObservedStatesAndEvents(viewModel) { states, events ->
            viewModel.getString()

            val expected = ShowSnackBar(R.string.playground_error_missing_key.toString())
            assertEquals(expected, events.single())
            assertTrue(states.isEmpty())
        }
    }

    private fun createViewModel(
        currentKey: String? = null,
        currentValue: String? = null,
        shouldCommit: Boolean? = null,
        stagedEntries: List<KeyValueEntry>? = null,
    ): PlaygroundViewModel {
        val savedStateHandle = SavedStateHandle()
        savedStateHandle["currentKey"] = currentKey
        savedStateHandle["currentValue"] = currentValue
        savedStateHandle["shouldCommit"] = shouldCommit
        savedStateHandle["stagedEntries"] = stagedEntries?.let(::ArrayList)
        return PlaygroundViewModel(
            savedStateHandle = savedStateHandle,
            dispatchersProvider = TestDispatcherProvider(),
            playgroundRepository = playgroundRepository,
            resourcesProvider = resourcesProvider,
        )
    }

    private inline fun TestScope.withObservedStatesAndEvents(
        viewModel: PlaygroundViewModel,
        crossinline block: (ArrayList<PlaygroundUiState>, ArrayList<Event>) -> Unit,
    ) {
        val states = ArrayList<PlaygroundUiState>()
        val events = ArrayList<Event>()
        val stateJob = launch(dispatchersProvider.io) {
            viewModel.state.drop(1).toList(states)
        }
        val eventJob = launch(dispatchersProvider.default) {
            viewModel.event.toList(events)
        }
        block(states, events)
        stateJob.cancel()
        eventJob.cancel()
    }
}
