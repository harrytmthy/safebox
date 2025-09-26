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
import androidx.annotation.VisibleForTesting
import com.harrytmthy.safebox.extensions.toBytes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel.MapMode.READ_WRITE

/**
 * Memory-mapped storage engine used by SafeBox to persist encrypted key-value entries.
 *
 * **Segmented growth:** the file is split into fixed-size pages (1 MiB by default),
 * each backed by its own [MappedByteBuffer]. Pages are added on demand as space runs out,
 * removing the 1 MiB ceiling of earlier implementations.
 *
 * **Tail-only writes:** new entries are always appended at the tail of a page. On overwrite,
 * the old entry is compacted away to close the hole, and the replacement is appended at the
 * tail of a suitable page (or in a newly allocated page if none fit).
 *
 * **Layout per entry:**
 * - `keyLength: Short`
 * - `valueLength: Int`
 * - `keyBytes: ByteArray`
 * - `valueBytes: ByteArray`
 */
internal class SafeBoxBlobStore private constructor(private val file: File) {

    private val channel = RandomAccessFile(file, "rw").channel

    private val buffers = ArrayList<MappedByteBuffer>()

    @VisibleForTesting
    internal val entryMetas = HashMap<Bytes, EntryMeta>()

    private val perPageEncryptedKeys = ArrayList<HashSet<Bytes>>()

    private val writeMutex = Mutex()

    private val nextWritePositions = ArrayList<Int>()

    init {
        var pagePosition = 0L
        do {
            buffers.add(channel.map(READ_WRITE, pagePosition, BUFFER_CAPACITY))
            perPageEncryptedKeys.add(HashSet())
            nextWritePositions.add(0)
            pagePosition += BUFFER_CAPACITY
        } while (pagePosition < file.length())
    }

    /**
     * Scans all mapped pages and reconstructs entries and per-page tails.
     *
     * Iterates page by page, reading entries until a zero key-length is found or the page
     * boundary is reached. Returns the encrypted key-value pairs.
     */
    internal suspend fun loadPersistedEntries(): Map<Bytes, ByteArray> =
        writeMutex.withLock {
            val entries = HashMap<Bytes, ByteArray>()
            var currentPage = 0
            var offset = 0
            while (currentPage < buffers.size) {
                val buffer = buffers[currentPage]
                buffer.position(offset)
                val keyLength = buffer.short.toInt()
                if (keyLength == 0) {
                    nextWritePositions[currentPage] = offset
                    if (currentPage == buffers.lastIndex) {
                        break
                    }
                    currentPage++
                    offset = 0
                    continue
                }
                val valueLength = buffer.int
                val entrySize = HEADER_SIZE + keyLength + valueLength
                val encryptedKey = ByteArray(keyLength).also(buffer::get).toBytes()
                val encryptedValue = ByteArray(valueLength).also(buffer::get)
                entries[encryptedKey] = encryptedValue
                entryMetas[encryptedKey] = EntryMeta(currentPage, offset, entrySize)
                perPageEncryptedKeys[currentPage].add(encryptedKey)
                offset += HEADER_SIZE + keyLength + valueLength
                when {
                    offset.toLong() > BUFFER_CAPACITY -> error("SafeBox blob store is corrupted!")
                    offset.toLong() == BUFFER_CAPACITY -> {
                        nextWritePositions[currentPage] = offset
                        currentPage++
                        offset = 0
                    }
                }
            }
            entries
        }

    internal fun contains(encryptedKey: Bytes): Boolean {
        return entryMetas.containsKey(encryptedKey)
    }

    /**
     * Appends a new encrypted key–value entry at a page tail.
     *
     * If the key already exists, the old entry's page is compacted, and the new entry is
     * appended at the tail of a suitable page. Pages are scanned from the first to the last
     * to reuse reclaimed space. A new page is allocated if none fits. Entries never cross
     * page boundaries.
     *
     * @param encryptedKey The encrypted key to store.
     * @param encryptedValue The encrypted value to associate with the key.
     *
     * @throws IllegalStateException if entry size exceeds the configured page capacity
     * or total page limit is reached.
     */
    internal suspend fun write(encryptedKey: Bytes, encryptedValue: ByteArray) {
        val entrySize = HEADER_SIZE + encryptedKey.value.size + encryptedValue.size
        if (entrySize > BUFFER_CAPACITY) {
            error("Failed to write entry with size $entrySize (max: $BUFFER_CAPACITY bytes)!")
        }
        writeMutex.withLock {
            val entry = entryMetas[encryptedKey]
            var page = entry?.page ?: buffers.lastIndex
            for (currentPage in buffers.indices) {
                val prevSize = entry?.size?.takeIf { currentPage == entry.page } ?: 0
                if (nextWritePositions[currentPage] - prevSize + entrySize <= BUFFER_CAPACITY) {
                    page = currentPage
                    break
                }
                if (currentPage == buffers.lastIndex) {
                    addNewPage()
                    page = currentPage + 1
                    break
                }
            }
            if (entry != null) {
                val remainingOffset = entry.offset + entry.size
                val remainingSize = nextWritePositions[entry.page] - remainingOffset
                shiftRemainingBytes(entry.page, remainingSize, remainingOffset, entry.offset)
                updateEntryMetaOffsets(entry.page, entry.offset + entry.size, entry.size)
                if (page != entry.page) {
                    perPageEncryptedKeys[entry.page].remove(encryptedKey)
                }
            }
            writeAtPage(page, encryptedKey, encryptedValue, entrySize)
        }
    }

    /**
     * Deletes one or more entries from the blob storage by their keys.
     *
     * For each key, performs an in-page left-shift of the remaining bytes to close the hole,
     * zero-fills the trailing gap, and adjusts metadata offsets of all subsequent entries in
     * that page.
     *
     * @param encryptedKeys Vararg array of keys to delete.
     */
    internal suspend fun delete(vararg encryptedKeys: Bytes) {
        writeMutex.withLock {
            for (encryptedKey in encryptedKeys) {
                if (!entryMetas.containsKey(encryptedKey)) {
                    continue
                }
                val entry = entryMetas.getValue(encryptedKey)
                shiftRemainingBytes(
                    page = entry.page,
                    remainingSize = nextWritePositions[entry.page] - (entry.offset + entry.size),
                    fromOffset = entry.offset + entry.size,
                    toOffset = entry.offset,
                )
                updateEntryMetaOffsets(entry.page, entry.offset + entry.size, entry.size)
                perPageEncryptedKeys[entry.page].remove(encryptedKey)
            }
            entryMetas -= encryptedKeys
        }
    }

    /**
     * Deletes all data and shrinks the file back to a single page.
     */
    internal suspend fun deleteAll() {
        writeMutex.withLock {
            for (page in buffers.lastIndex downTo 0) {
                buffers[page].position(0)
                buffers[page].put(ByteArray(nextWritePositions[page]))
                buffers[page].force()
                if (page > 0) {
                    buffers.removeAt(page)
                    nextWritePositions.removeAt(page)
                    perPageEncryptedKeys.removeAt(page)
                } else {
                    nextWritePositions[0] = 0
                    perPageEncryptedKeys[0].clear()
                }
            }
            entryMetas.clear()
            channel.truncate(BUFFER_CAPACITY)
            channel.force(true)
        }
    }

    /**
     * Returns the name of the backing file, excluding its extension.
     *
     * Useful for diagnostics and mapping state to a human-readable identifier.
     */
    internal fun getFileName(): String = file.nameWithoutExtension

    /**
     * Closes the underlying file channel and releases associated resources.
     */
    internal suspend fun closeWhenIdle() {
        writeMutex.withLock {
            channel.close()
        }
    }

    private fun writeAtPage(page: Int, encryptedKey: Bytes, encryptedValue: ByteArray, size: Int) {
        val buffer = buffers[page]
        buffer.position(nextWritePositions[page])
        buffer.putShort(encryptedKey.value.size.toShort())
        buffer.putInt(encryptedValue.size)
        buffer.put(encryptedKey.value)
        buffer.put(encryptedValue)
        buffer.force()
        entryMetas[encryptedKey] = EntryMeta(page, nextWritePositions[page], size)
        perPageEncryptedKeys[page].add(encryptedKey)
        nextWritePositions[page] += size
    }

    private fun shiftRemainingBytes(page: Int, remainingSize: Int, fromOffset: Int, toOffset: Int) {
        val buffer = buffers[page]
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
            buffer.force()
        }
        val gap = nextWritePositions[page] - buffer.position()
        if (gap > 0) {
            buffer.put(ByteArray(gap))
            buffer.force()
        }
        nextWritePositions[page] -= fromOffset - toOffset
    }

    private fun updateEntryMetaOffsets(page: Int, fromOffset: Int, delta: Int) {
        for (key in perPageEncryptedKeys[page]) {
            val entry = entryMetas.getValue(key)
            if (entry.offset >= fromOffset) {
                entryMetas[key] = entry.copy(offset = entry.offset - delta)
            }
        }
    }

    private fun addNewPage() {
        if (buffers.size == MAX_PAGE) {
            error("Maximum $MAX_PAGE is reached for SafeBox \"${file.nameWithoutExtension}\"!")
        }
        val newBuffer = try {
            channel.map(READ_WRITE, buffers.size * BUFFER_CAPACITY, BUFFER_CAPACITY)
        } catch (e: IOException) {
            // TODO: Fallback to recovery file (#134)
            throw e
        }
        buffers.add(newBuffer)
        nextWritePositions.add(0)
        perPageEncryptedKeys.add(HashSet())
    }

    @VisibleForTesting
    internal data class EntryMeta(val page: Int, val offset: Int, val size: Int)

    internal companion object {

        const val MAX_PAGE = 64

        const val BUFFER_CAPACITY = 1024 * 1024L // 1 MiB

        const val HEADER_SIZE = 6 // 2 bytes for key length, 4 bytes for value length

        internal fun create(context: Context, fileName: String): SafeBoxBlobStore {
            val file = File(context.noBackupFilesDir, "$fileName.bin")
            if (!file.exists()) {
                file.createNewFile()
            }
            return SafeBoxBlobStore(file)
        }
    }
}
