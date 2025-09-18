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

import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.harrytmthy.safebox.demo.R
import com.harrytmthy.safebox.demo.SafeBoxApplication
import com.harrytmthy.safebox.demo.databinding.ActivityPlaygroundBinding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

class PlaygroundActivity : ComponentActivity() {

    @Inject
    lateinit var viewModelFactory: PlaygroundViewModel.Factory

    private val viewModel: PlaygroundViewModel by viewModels { viewModelFactory }

    private lateinit var binding: ActivityPlaygroundBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        (applicationContext as SafeBoxApplication).playgroundComponent.inject(this)
        super.onCreate(savedInstanceState)
        binding = ActivityPlaygroundBinding.inflate(LayoutInflater.from(this))
        setContentView(binding.root)
        observeState()
        observeEvent()
        setupListeners()
    }

    private fun observeState() {
        viewModel.state.collectOnStarted {
            renderKeyValuePair(currentKey, currentValue)
            renderCommitSwitch(shouldCommit)
            renderApplyButton(shouldCommit)
        }
    }

    private fun observeEvent() {
        viewModel.event.collectOnStarted {
            if (this is PlaygroundViewModel.Event.ShowSnackBar) {
                Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun setupListeners() {
        with(binding) {
            etKey.doOnTextChanged { text, _, _, _ ->
                viewModel.updateCurrentKey(text?.toString().orEmpty())
            }
            etValue.doOnTextChanged { text, _, _, _ ->
                viewModel.updateCurrentValue(text?.toString().orEmpty())
            }
            ctaGet.setOnClickListener { viewModel.getString() }
            ctaCommit.setOnCheckedChangeListener { button, _ ->
                if (button.isPressed) {
                    viewModel.toggleCommit()
                }
            }
            ctaApply.setOnClickListener { viewModel.applyOrCommit() }
        }
    }

    private fun renderKeyValuePair(currentKey: String, currentValue: String) {
        if (currentKey != binding.etKey.text.toString()) {
            binding.etKey.setText(currentKey)
        }
        if (currentValue != binding.etValue.text.toString()) {
            binding.etValue.setText(currentValue)
        }
    }

    private fun renderCommitSwitch(shouldCommit: Boolean) {
        binding.ctaCommit.isChecked = shouldCommit
    }

    private fun renderApplyButton(shouldCommit: Boolean) {
        binding.ctaApply.text = if (!shouldCommit) {
            getString(R.string.playground_cta_apply)
        } else {
            getString(R.string.playground_cta_commit)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        viewModel.saveUiState()
        super.onSaveInstanceState(outState)
    }

    private inline fun <T> Flow<T>.collectOnStarted(crossinline block: suspend T.() -> Unit) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                collect { block(it) }
            }
        }
    }
}
