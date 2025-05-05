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

import androidx.annotation.VisibleForTesting
import com.harrytmthy.safebox.extensions.toBytes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Memory-mapped storage engine used by SafeBox to persist encrypted key-value entries.
 *
 * This class provides low-level binary I/O with append-only semantics.
 * It's designed to be loaded once and kept in-memory via a higher-level index (e.g. in SafeBox).
 *
 * Keys and values are stored as length-prefixed byte sequences:
 * [keyLength:Short][valueLength:Int][keyBytes:ByteArray][valueBytes:ByteArray]
 */
internal class SafeBoxBlobStore(file: File) {

    private val channel: FileChannel
    private val buffer: MappedByteBuffer

    private val readMutex = Mutex()
    private val writeMutex = Mutex()

    @VisibleForTesting
    internal val entryMetaByKey = LinkedHashMap<Bytes, EntryMeta>()

    private val nextWritePosition: Int
        get() = entryMetaByKey.values.lastOrNull()?.run { offset + size } ?: 0

    init {
        if (!file.exists()) file.createNewFile()
        val raf = RandomAccessFile(file, "rw")
        channel = raf.channel
        buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, BUFFER_CAPACITY.toLong())
    }

    /**
     * Loads all key-value pairs stored in the blob file into memory.
     * Only entries up to the current write position are considered valid.
     *
     * @return A map of stored keys and their corresponding byte array values.
     */
    internal suspend fun loadAll(): Map<Bytes, ByteArray> = readMutex.withLock {
        val result = LinkedHashMap<Bytes, ByteArray>()
        var offset = 0
        while (offset + HEADER_SIZE <= buffer.capacity()) {
            buffer.position(offset)
            val keyLength = buffer.short.toInt()
            val valueLength = buffer.int
            val entrySize = HEADER_SIZE + keyLength + valueLength
            if (keyLength == 0 || offset + entrySize > buffer.capacity()) {
                break
            }
            val key = ByteArray(keyLength).also(buffer::get).toBytes()
            result[key] = ByteArray(valueLength).also(buffer::get)
            entryMetaByKey[key] = EntryMeta(offset, entrySize)
            offset += HEADER_SIZE + keyLength + valueLength
        }
        result
    }

    /**
     * Appends a new encrypted key–value entry to the blob file.
     *
     * If the key does not exist, it appends at the next available offset. If the key already
     * exists, the entry is overwritten in-place if sizes match. Otherwise, the blob is compacted
     * to accommodate the size difference before rewriting.
     *
     * @param key The encrypted key to store, typically already processed by the cipher layer.
     * @param value The encrypted value to associate with the key.
     *
     * @throws IllegalStateException if the blob file does not have enough remaining capacity.
     */
    internal suspend fun write(key: ByteArray, value: ByteArray) {
        writeMutex.withLock {
            val realKey = key.toBytes()
            if (!entryMetaByKey.contains(realKey)) {
                writeAtOffset(realKey, value)
            } else {
                overwrite(realKey, value)
            }
        }
    }

    /**
     * Deletes one or more entries from the blob storage by their keys.
     *
     * Performs a logical deletion by zeroing-out the entry data in-place, followed by compaction
     * to maintain contiguous storage and update metadata offsets accordingly.
     *
     * @param keys Vararg array of keys to delete.
     */
    internal suspend fun delete(vararg keys: ByteArray) {
        writeMutex.withLock {
            val entries = entryMetaByKey.values.toList()
            val realKeys = keys.map { it.toBytes() }
            for (realKey in realKeys) {
                val currentIndex = entryMetaByKey.keys.indexOf(realKey)
                if (currentIndex == -1) {
                    continue
                }
                val entry = entries[currentIndex]
                shiftRemainingBytes(
                    remainingSize = nextWritePosition - (entry.offset + entry.size),
                    fromOffset = entry.offset + entry.size,
                    toOffset = entry.offset,
                )
                updateEntryMetaOffsets(currentIndex + 1, entry.offset, entries)
            }
            buffer.force()
            realKeys.forEach(entryMetaByKey::remove)
        }
    }

    /**
     * Closes the underlying file channel and releases associated resources.
     * Must be called when SafeBoxBlobStore is no longer in use to prevent memory leaks.
     */
    internal fun close() {
        channel.close()
    }

    private fun writeAtOffset(
        realKey: Bytes,
        value: ByteArray,
        offset: Int = nextWritePosition,
    ) {
        val entrySize = HEADER_SIZE + realKey.key.size + value.size
        if (offset + entrySize > buffer.capacity()) {
            error("Cannot write at offset. Not enough buffer capacity.")
        }
        buffer.position(offset)
        buffer.putShort(realKey.key.size.toShort())
        buffer.putInt(value.size)
        buffer.put(realKey.key)
        buffer.put(value)
        buffer.force()
        entryMetaByKey[realKey] = EntryMeta(offset, entrySize)
    }

    private fun overwrite(realKey: Bytes, value: ByteArray) {
        val entry = entryMetaByKey.getValue(realKey)
        val newSize = HEADER_SIZE + realKey.key.size + value.size
        if (newSize == entry.size) {
            writeAtOffset(realKey, value, entry.offset)
            return
        }
        shiftRemainingBytes(
            remainingSize = nextWritePosition - (entry.offset + entry.size),
            fromOffset = entry.offset + entry.size,
            toOffset = entry.offset + newSize,
        )
        writeAtOffset(realKey, value, entry.offset)
        val currentIndex = entryMetaByKey.keys.indexOf(realKey)
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
        while (buffer.position() < nextWritePosition) {
            buffer.put(0.toByte())
        }
    }

    private fun updateEntryMetaOffsets(
        startIndex: Int,
        initialOffset: Int,
        entries: List<EntryMeta> = entryMetaByKey.values.toList(),
    ) {
        var offset = initialOffset
        for (index in startIndex until entries.size) {
            val key = entryMetaByKey.keys.elementAt(index)
            val updatedEntry = entries[index].copy(offset = offset)
            entryMetaByKey[key] = updatedEntry
            offset += updatedEntry.size
        }
    }

    @VisibleForTesting
    internal data class EntryMeta(val offset: Int, val size: Int)

    private companion object {
        const val BUFFER_CAPACITY = 1024 * 1024 // 1MB
        const val HEADER_SIZE = 6 // 2 bytes for key length, 4 bytes for value length
    }
}
