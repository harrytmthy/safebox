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

package com.harrytmthy.safebox.storage

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.harrytmthy.safebox.extensions.safeBoxScope
import com.harrytmthy.safebox.extensions.toBytes
import com.harrytmthy.safebox.strategy.ValueFallbackStrategy
import com.harrytmthy.safebox.strategy.ValueFallbackStrategy.ERROR
import com.harrytmthy.safebox.strategy.ValueFallbackStrategy.WARN
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Memory-mapped storage engine used by SafeBox to persist encrypted key-value entries.
 *
 * This class provides low-level binary I/O with append-only semantics.
 * It's designed to be loaded once and kept in-memory via a higher-level index (e.g. in SafeBox).
 *
 * Keys and values are stored as length-prefixed byte sequences:
 * [keyLength:Short][valueLength:Int][keyBytes:ByteArray][valueBytes:ByteArray]
 */
internal class SafeBoxBlobStore private constructor(
    ioDispatcher: CoroutineDispatcher,
    private val channel: FileChannel,
) {

    private val buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, BUFFER_CAPACITY.toLong())

    private val entries = HashMap<Bytes, ByteArray>()

    @VisibleForTesting
    internal val entryMetas = LinkedHashMap<Bytes, EntryMeta>()

    private val initialLoadCompleted = AtomicBoolean(false)

    private val initialLoadStrategy = AtomicReference<ValueFallbackStrategy>(WARN)

    private val writeMutex = Mutex()

    private val nextWritePosition: Int
        get() = entryMetas.values.lastOrNull()?.run { offset + size } ?: 0

    init {
        safeBoxScope.launch(ioDispatcher) {
            writeMutex.withLock {
                var offset = 0
                while (offset + HEADER_SIZE <= buffer.capacity()) {
                    buffer.position(offset)
                    val keyLength = buffer.short.toInt()
                    val valueLength = buffer.int
                    val entrySize = HEADER_SIZE + keyLength + valueLength
                    if (keyLength == 0 || offset + entrySize > buffer.capacity()) {
                        break
                    }
                    val encryptedKey = ByteArray(keyLength).also(buffer::get).toBytes()
                    val encryptedValue = ByteArray(valueLength).also(buffer::get)
                    entries[encryptedKey] = encryptedValue
                    entryMetas[encryptedKey] = EntryMeta(offset, entrySize)
                    offset += HEADER_SIZE + keyLength + valueLength
                }
                initialLoadCompleted.set(true)
            }
        }
    }

    internal fun get(key: Bytes): ByteArray? {
        checkInitialLoad()
        return entries[key]
    }

    internal fun getAll(): Map<Bytes, ByteArray> {
        checkInitialLoad()
        return entries
    }

    internal fun contains(encryptedKey: Bytes): Boolean {
        checkInitialLoad()
        return entries.containsKey(encryptedKey)
    }

    internal fun setInitialLoadStrategy(fallbackStrategy: ValueFallbackStrategy) {
        initialLoadStrategy.set(fallbackStrategy)
    }

    /**
     * Appends a new encrypted key–value entry to the blob file.
     *
     * If the key does not exist, it appends at the next available offset. If the key already
     * exists, the entry is overwritten in-place if sizes match. Otherwise, the blob is compacted
     * to accommodate the size difference before rewriting.
     *
     * @param encryptedKey The encrypted key to store.
     * @param encryptedValue The encrypted value to associate with the key.
     *
     * @throws IllegalStateException if the blob file does not have enough remaining capacity.
     */
    internal suspend fun write(encryptedKey: Bytes, encryptedValue: ByteArray) {
        writeMutex.withLock {
            if (!entryMetas.contains(encryptedKey)) {
                writeAtOffset(encryptedKey, encryptedValue)
            } else {
                overwrite(encryptedKey, encryptedValue)
            }
        }
    }

    /**
     * Deletes one or more entries from the blob storage by their keys.
     *
     * Performs a logical deletion by zeroing-out the entry data in-place, followed by compaction
     * to maintain contiguous storage and update metadata offsets accordingly.
     *
     * @param encryptedKeys Vararg array of keys to delete.
     */
    internal suspend fun delete(vararg encryptedKeys: Bytes) {
        writeMutex.withLock {
            val metas = entryMetas.values.toList()
            for (encryptedKey in encryptedKeys) {
                val currentIndex = entryMetas.keys.indexOf(encryptedKey)
                if (currentIndex == -1) {
                    continue
                }
                val entry = metas[currentIndex]
                shiftRemainingBytes(
                    remainingSize = nextWritePosition - (entry.offset + entry.size),
                    fromOffset = entry.offset + entry.size,
                    toOffset = entry.offset,
                )
                updateEntryMetaOffsets(currentIndex + 1, entry.offset, metas)
            }
            buffer.force()
            entries -= encryptedKeys
            entryMetas -= encryptedKeys
        }
    }

    /**
     * Deletes all entries from the blob storage.
     *
     * Performs a complete logical wipe by zeroing-out all bytes up to the last written position,
     * ensuring previously stored data cannot be recovered. Also clears all in-memory metadata
     * to reset the store to its initial empty state.
     *
     * @return a set of [Bytes] keys that were removed, used for notifying listeners.
     */
    suspend fun deleteAll(): Set<Bytes> =
        writeMutex.withLock {
            buffer.position(0)
            buffer.put(ByteArray(nextWritePosition))
            buffer.force()
            val keys = entries.keys.toSet()
            entries.clear()
            entryMetas.clear()
            keys
        }

    /**
     * Closes the underlying file channel and releases associated resources.
     * Must be called when SafeBoxBlobStore is no longer in use to prevent memory leaks.
     */
    internal fun close() {
        channel.close()
    }

    private fun writeAtOffset(
        encryptedKey: Bytes,
        encryptedValue: ByteArray,
        offset: Int = nextWritePosition,
    ) {
        val entrySize = HEADER_SIZE + encryptedKey.value.size + encryptedValue.size
        if (offset + entrySize > buffer.capacity()) {
            error("Cannot write at offset. Not enough buffer capacity.")
        }
        buffer.position(offset)
        buffer.putShort(encryptedKey.value.size.toShort())
        buffer.putInt(encryptedValue.size)
        buffer.put(encryptedKey.value)
        buffer.put(encryptedValue)
        buffer.force()
        entries[encryptedKey] = encryptedValue
        entryMetas[encryptedKey] = EntryMeta(offset, entrySize)
    }

    private fun overwrite(encryptedKey: Bytes, encryptedValue: ByteArray) {
        val entry = entryMetas.getValue(encryptedKey)
        val newSize = HEADER_SIZE + encryptedKey.value.size + encryptedValue.size
        if (newSize == entry.size) {
            writeAtOffset(encryptedKey, encryptedValue, entry.offset)
            return
        }
        shiftRemainingBytes(
            remainingSize = nextWritePosition - (entry.offset + entry.size),
            fromOffset = entry.offset + entry.size,
            toOffset = entry.offset + newSize,
        )
        writeAtOffset(encryptedKey, encryptedValue, entry.offset)
        val currentIndex = entryMetas.keys.indexOf(encryptedKey)
        updateEntryMetaOffsets(currentIndex + 1, entry.offset + newSize)
        buffer.force()
    }

    private fun shiftRemainingBytes(remainingSize: Int, fromOffset: Int, toOffset: Int) {
        if (toOffset + remainingSize > buffer.capacity()) {
            error("Cannot shift the remaining bytes. Not enough buffer capacity.")
        }
        if (remainingSize == 0) {
            buffer.position(toOffset)
        } else {
            buffer.position(fromOffset)
            val remainingBytes = ByteArray(remainingSize).apply(buffer::get)
            buffer.position(toOffset)
            buffer.put(remainingBytes)
            if (fromOffset < toOffset) {
                return
            }
        }
        val gap = nextWritePosition - buffer.position()
        if (gap > 0) {
            buffer.put(ByteArray(gap))
        }
    }

    private fun updateEntryMetaOffsets(
        startIndex: Int,
        initialOffset: Int,
        entries: List<EntryMeta> = entryMetas.values.toList(),
    ) {
        var offset = initialOffset
        for (index in startIndex until entries.size) {
            val key = entryMetas.keys.elementAt(index)
            val updatedEntry = entries[index].copy(offset = offset)
            entryMetas[key] = updatedEntry
            offset += updatedEntry.size
        }
    }

    private fun checkInitialLoad() {
        if (initialLoadCompleted.get()) {
            return
        }
        when (initialLoadStrategy.get()) {
            ERROR -> error("Initial load is not yet completed.")
            WARN -> Log.w("SafeBox", "A value was retrieved before initial load completed.")
        }
    }

    @VisibleForTesting
    internal data class EntryMeta(val offset: Int, val size: Int)

    internal companion object {
        const val BUFFER_CAPACITY = 1024 * 1024 // 1MB
        const val HEADER_SIZE = 6 // 2 bytes for key length, 4 bytes for value length

        internal fun create(
            context: Context,
            fileName: String,
            ioDispatcher: CoroutineDispatcher,
        ): SafeBoxBlobStore {
            val file = File(context.noBackupFilesDir, "$fileName.bin")
            if (!file.exists()) {
                file.createNewFile()
            }
            val raf = RandomAccessFile(file, "rw")
            return SafeBoxBlobStore(ioDispatcher, raf.channel)
        }
    }
}
