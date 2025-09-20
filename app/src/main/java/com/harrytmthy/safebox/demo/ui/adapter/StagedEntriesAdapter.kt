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

package com.harrytmthy.safebox.demo.ui.adapter

import android.content.res.ColorStateList
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.text.bold
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.harrytmthy.safebox.demo.R
import com.harrytmthy.safebox.demo.databinding.ItemClearedEntryBinding
import com.harrytmthy.safebox.demo.databinding.ItemKeyValueEntryBinding
import com.harrytmthy.safebox.demo.ui.enums.Action
import com.harrytmthy.safebox.demo.ui.model.ClearedEntry
import com.harrytmthy.safebox.demo.ui.model.Entry
import com.harrytmthy.safebox.demo.ui.model.KeyValueEntry

class StagedEntriesAdapter : ListAdapter<Entry, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    override fun getItemViewType(position: Int): Int =
        when (getItem(position)) {
            is KeyValueEntry -> KEY_VALUE_ITEM_TYPE
            is ClearedEntry -> CLEARED_ITEM_TYPE
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val viewHolder = when (viewType) {
            KEY_VALUE_ITEM_TYPE -> {
                val binding = ItemKeyValueEntryBinding.inflate(inflater, parent, false)
                KeyValueEntryViewHolder(binding)
            }
            CLEARED_ITEM_TYPE -> {
                val binding = ItemClearedEntryBinding.inflate(inflater, parent, false)
                ClearedViewHolder(binding)
            }
            else -> error("Unknown viewType")
        }
        return viewHolder
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        if (item is KeyValueEntry) {
            (holder as KeyValueEntryViewHolder).bind(item)
        }
    }

    class KeyValueEntryViewHolder(
        private val binding: ItemKeyValueEntryBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(model: KeyValueEntry) {
            val keyPrefix = binding.root.context
                ?.getString(R.string.playground_entry_prefix_key)
                ?: return
            val valuePrefix = binding.root.context
                ?.getString(R.string.playground_entry_prefix_value)
                ?: return
            binding.tvFirstRow.text = SpannableStringBuilder()
                .bold { append(keyPrefix) }
                .append(model.key)
            binding.tvSecondRow.text = SpannableStringBuilder()
                .bold { append(valuePrefix) }
                .append(model.value)
            binding.tvSecondRow.isVisible = model.action == Action.PUT
            renderActionLabel(model.action)
        }

        private fun renderActionLabel(action: Action) = with(binding) {
            when (action) {
                Action.PUT -> {
                    tvAction.backgroundTintList = ColorStateList.valueOf(Color.GREEN)
                    tvAction.text = root.context?.getString(R.string.playground_entry_put_label)
                }
                Action.REMOVE -> {
                    tvAction.backgroundTintList = ColorStateList.valueOf(Color.RED)
                    tvAction.text = root.context?.getString(R.string.playground_entry_remove_label)
                }
            }
        }
    }

    class ClearedViewHolder(
        binding: ItemClearedEntryBinding,
    ) : RecyclerView.ViewHolder(binding.root)

    private companion object {

        private const val KEY_VALUE_ITEM_TYPE = 0

        private const val CLEARED_ITEM_TYPE = 1

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Entry>() {

            override fun areItemsTheSame(oldItem: Entry, newItem: Entry): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Entry, newItem: Entry): Boolean =
                oldItem == newItem
        }
    }
}
