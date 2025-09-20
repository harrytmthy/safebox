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

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.harrytmthy.safebox.demo.R
import com.harrytmthy.safebox.demo.core.DispatchersProvider
import com.harrytmthy.safebox.demo.core.ResourcesProvider
import com.harrytmthy.safebox.demo.domain.PlaygroundRepository
import com.harrytmthy.safebox.demo.ui.enums.Action
import com.harrytmthy.safebox.demo.ui.model.ClearedEntry
import com.harrytmthy.safebox.demo.ui.model.KeyValueEntry
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

class PlaygroundViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val dispatchersProvider: DispatchersProvider,
    private val playgroundRepository: PlaygroundRepository,
    private val resourcesProvider: ResourcesProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(PlaygroundUiState.create(savedStateHandle))
    val state = _state.asStateFlow()

    private val _event = MutableSharedFlow<Event>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val event = _event.asSharedFlow()

    fun updateCurrentKey(key: String) {
        _state.update { it.copy(currentKey = key) }
    }

    fun updateCurrentValue(value: String) {
        _state.update { it.copy(currentValue = value) }
    }

    fun getString() {
        val key = _state.value.currentKey
        if (key.isEmpty()) {
            showSnackBar(R.string.playground_error_missing_key)
            return
        }
        if (!playgroundRepository.contains(key)) {
            showSnackBar(R.string.playground_error_non_existent_key, key)
            return
        }
        viewModelScope.launch(dispatchersProvider.io) {
            val value = runCatching { playgroundRepository.getString(key) }
                .getOrElse {
                    showSnackBar(R.string.playground_error_get_failed, it.message.orEmpty())
                    return@launch
                }
            if (value != null) {
                _state.update { it.copy(currentValue = value) }
            } else {
                showSnackBar(R.string.playground_error_non_existent_key, key)
            }
        }
    }

    fun putString() {
        addNewEntry(Action.PUT)
    }

    fun remove() {
        addNewEntry(Action.REMOVE)
    }

    fun clear() {
        val state = _state.value
        if (state.stagedEntries.firstOrNull() != ClearedEntry) {
            _state.update { it.copy(stagedEntries = listOf(ClearedEntry) + state.stagedEntries) }
        }
    }

    private fun addNewEntry(action: Action) {
        val state = _state.value
        if (state.currentKey.isEmpty()) {
            showSnackBar(R.string.playground_error_missing_key)
            return
        }
        val newEntry = KeyValueEntry(
            id = UUID.randomUUID().toString(),
            key = state.currentKey,
            value = state.currentValue,
            action = action,
        )
        _state.update {
            it.copy(
                currentKey = "",
                currentValue = "",
                stagedEntries = state.stagedEntries + newEntry,
            )
        }
    }

    fun showConfirmationDialog() {
        val event = with(resourcesProvider) {
            Event.ShowConfirmation(
                title = getString(R.string.playground_confirmation_dialog_title),
                message = getString(R.string.playground_confirmation_dialog_message),
                positiveButtonText = getString(R.string.playground_confirmation_dialog_positive),
                negativeButtonText = getString(R.string.playground_confirmation_dialog_negative),
            )
        }
        _event.tryEmit(event)
    }

    fun clearStagedEntries() {
        _state.update { it.copy(stagedEntries = emptyList()) }
    }

    fun applyOrCommit() {
        val state = _state.value
        if (state.stagedEntries.isEmpty()) {
            showSnackBar(R.string.playground_error_no_staged_entry)
            return
        }
        viewModelScope.launch(dispatchersProvider.io) {
            runCatching {
                playgroundRepository.saveEntries(
                    entries = state.stagedEntries.filterIsInstance<KeyValueEntry>(),
                    shouldClear = state.stagedEntries.firstOrNull() == ClearedEntry,
                    shouldCommit = state.shouldCommit,
                )
            }.getOrElse {
                showSnackBar(R.string.playground_error_commit_failed, it.message.orEmpty())
                return@launch
            }
            showSnackBar(R.string.playground_success)
            _state.update {
                it.copy(currentKey = "", currentValue = "", stagedEntries = emptyList())
            }
        }
    }

    fun toggleCommit() {
        _state.update { it.copy(shouldCommit = !it.shouldCommit) }
    }

    fun saveUiState() {
        val state = _state.value
        savedStateHandle["currentKey"] = state.currentKey
        savedStateHandle["currentValue"] = state.currentValue
        savedStateHandle["shouldCommit"] = state.shouldCommit
        savedStateHandle["shouldClear"] = state.stagedEntries.firstOrNull() is ClearedEntry
        val stagedEntries = ArrayList<KeyValueEntry>(state.stagedEntries.size)
        for (entry in state.stagedEntries) {
            if (entry is KeyValueEntry) {
                stagedEntries.add(entry)
            }
        }
        savedStateHandle["stagedEntries"] = stagedEntries
    }

    private fun showSnackBar(@StringRes resId: Int) {
        val message = resourcesProvider.getString(resId)
        _event.tryEmit(Event.ShowSnackBar(message))
    }

    private fun showSnackBar(@StringRes resId: Int, vararg formatArgs: Any) {
        val message = resourcesProvider.getString(resId, *formatArgs)
        _event.tryEmit(Event.ShowSnackBar(message))
    }

    sealed class Event {

        data class ShowSnackBar(val message: String) : Event()

        data class ShowConfirmation(
            val title: String,
            val message: String,
            val positiveButtonText: String,
            val negativeButtonText: String,
        ) : Event()
    }

    @Singleton
    class Factory @Inject constructor(
        private val dispatchersProvider: DispatchersProvider,
        private val playgroundRepository: PlaygroundRepository,
        private val resourcesProvider: ResourcesProvider,
    ) : ViewModelProvider.Factory {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T =
            PlaygroundViewModel(
                savedStateHandle = extras.createSavedStateHandle(),
                dispatchersProvider = dispatchersProvider,
                playgroundRepository = playgroundRepository,
                resourcesProvider = resourcesProvider,
            ) as T
    }
}
