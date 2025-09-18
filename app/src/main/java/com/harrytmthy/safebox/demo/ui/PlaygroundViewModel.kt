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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

    fun applyOrCommit() {
        val state = _state.value
        if (state.currentKey.isEmpty()) {
            showSnackBar(R.string.playground_error_missing_key)
            return
        }
        val currentEntry = state.currentKey to state.currentValue
        viewModelScope.launch(dispatchersProvider.io) {
            runCatching {
                playgroundRepository.saveEntries(linkedMapOf(currentEntry), state.shouldCommit)
            }.getOrElse {
                showSnackBar(R.string.playground_error_commit_failed, it.message.orEmpty())
                return@launch
            }
            showSnackBar(R.string.playground_success)
            _state.update { it.copy(currentKey = "", currentValue = "") }
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
