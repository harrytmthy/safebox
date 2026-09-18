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
import java.io.RandomAccessFile
import java.nio.channels.FileChannel.MapMode.READ_WRITE

/**
 * Shared, fixed-capacity recovery journal. Each record identifies its owning SafeBox file.
 * Replacement and deletion compact the remaining records.
 *
 * **Layout per entry:**
 * - `fileNameLength: Short`
 * - `keyLength: Short`
 * - `valueLength: Int`
 * - `fileNameBytes: ByteArray`
 * - `keyBytes: ByteArray`
 * - `valueBytes: ByteArray`
 */
internal class SafeBoxRecoveryBlobStore private constructor(
    file: File,
    private val beforeForce: (() -> Unit)? = null,
) {

    private val channel = RandomAccessFile(file, "rw").channel

    private val buffer = channel.map(READ_WRITE, 0, BUFFER_CAPACITY.toLong())

    private val entryMetasByFileName = HashMap<Bytes, HashMap<Bytes, EntryMeta>>()

    private val encryptedKeysByFileName = HashMap<Bytes, HashSet<Bytes>>()

    private val writeMutex = Mutex()

    private var pendingForce = false

    private var nextWritePosition = 0

    internal suspend fun loadPersistedEntries(fileName: Bytes): Map<Bytes, ByteArray> =
        writeMutex.withLock {
            entryMetasByFileName.clear()
            encryptedKeysByFileName.clear()
            val entries = HashMap<Bytes, ByteArray>()
            var offset = 0
            while (offset <= buffer.capacity()) {
                if (offset == buffer.capacity()) {
                    break
                }
                if (offset + HEADER_SIZE > buffer.capacity()) {
                    buffer.repairCorruptedBytes(offset)
                    break
                }
                buffer.position(offset)
                val fileNameSize = buffer.short.toInt()
                if (fileNameSize == 0) {
                    break
                }
                val keySize = buffer.short.toInt()
                val valueSize = buffer.int
                val tail = offset.toLong() + HEADER_SIZE + fileNameSize + keySize + valueSize
                if (fileNameSize < 0 || keySize < 0 || valueSize < 0 || tail > buffer.capacity()) {
                    buffer.repairCorruptedBytes(offset)
                    break
                }
                val fileNameBytes = ByteArray(fileNameSize).also(buffer::get).toBytes()
                val encryptedKey = ByteArray(keySize).also(buffer::get).toBytes()
                val encryptedValue = ByteArray(valueSize).also(buffer::get)
                val entrySize = tail.toInt() - offset
                if (fileNameBytes == fileName) {
                    entries[encryptedKey] = encryptedValue
                }
                val entryMeta = entryMetasByFileName.getOrPut(fileNameBytes) { HashMap() }
                entryMeta[encryptedKey] = EntryMeta(offset, entrySize)
                encryptedKeysByFileName.getOrPut(fileNameBytes) { HashSet() }.add(encryptedKey)
                offset += entrySize
            }
            nextWritePosition = offset
            entries
        }

    internal suspend fun closeWhenIdle() {
        writeMutex.withLock {
            channel.close()
        }
    }

    internal suspend fun write(
        fileName: Bytes,
        encryptedKey: Bytes,
        encryptedValue: ByteArray,
        forceNow: Boolean,
    ) {
        val entrySize = HEADER_SIZE + fileName.size + encryptedKey.size + encryptedValue.size
        if (entrySize > BUFFER_CAPACITY) {
            error("Failed to write entry with size $entrySize (max: $BUFFER_CAPACITY bytes)!")
        }
        writeMutex.withLock {
            val entry = entryMetasByFileName[fileName]?.get(encryptedKey)
            val prevSize = entry?.size ?: 0
            if (nextWritePosition - prevSize + entrySize > BUFFER_CAPACITY) {
                error("Failed to write entry. Not enough buffer capacity.")
            }
            pendingForce = true
            if (entry != null) {
                nextWritePosition = buffer.shiftLeft(
                    currentTail = nextWritePosition,
                    fromOffset = entry.offset + entry.size,
                    toOffset = entry.offset,
                )
                for (entryMetas in entryMetasByFileName.values) {
                    entryMetas.adjustOffsets(
                        keys = entryMetas.keys,
                        fromOffset = entry.offset + entry.size,
                        delta = entry.size,
                    )
                }
            }
            buffer.position(nextWritePosition)
            buffer.putShort(fileName.size.toShort())
            buffer.putShort(encryptedKey.size.toShort())
            buffer.putInt(encryptedValue.size)
            buffer.put(fileName.value)
            buffer.put(encryptedKey.value)
            buffer.put(encryptedValue)
            entryMetasByFileName.getOrPut(fileName) { HashMap() }[encryptedKey] =
                EntryMeta(nextWritePosition, entrySize)
            encryptedKeysByFileName.getOrPut(fileName) { HashSet() }.add(encryptedKey)
            nextWritePosition += entrySize
            if (forceNow) {
                forcePendingChangesLocked()
            }
        }
    }

    // Metadata follows mapped bytes. A failed force leaves only persistence uncertain.
    private fun forcePendingChangesLocked() {
        if (!pendingForce) {
            return
        }
        beforeForce?.invoke()
        buffer.force()
        pendingForce = false
    }

    internal suspend fun flushPendingChanges() {
        writeMutex.withLock {
            forcePendingChangesLocked()
        }
    }

    internal suspend fun delete(fileName: Bytes) {
        writeMutex.withLock {
            val encryptedKeys = encryptedKeysByFileName[fileName]?.toTypedArray() ?: emptyArray()
            deleteEntries(fileName, encryptedKeys)
        }
    }

    internal suspend fun delete(fileName: Bytes, vararg encryptedKeys: Bytes) {
        writeMutex.withLock {
            deleteEntries(fileName, encryptedKeys)
        }
    }

    private fun deleteEntries(fileName: Bytes, encryptedKeys: Array<out Bytes>) {
        val entryMeta = entryMetasByFileName[fileName]
        if (entryMeta != null) {
            for (encryptedKey in encryptedKeys) {
                val entry = entryMeta[encryptedKey] ?: continue
                pendingForce = true
                nextWritePosition = buffer.shiftLeft(
                    currentTail = nextWritePosition,
                    fromOffset = entry.offset + entry.size,
                    toOffset = entry.offset,
                )
                for (entryMetas in entryMetasByFileName.values) {
                    entryMetas.adjustOffsets(
                        keys = entryMetas.keys,
                        fromOffset = entry.offset + entry.size,
                        delta = entry.size,
                    )
                }
                entryMeta -= encryptedKey
                encryptedKeysByFileName.getValue(fileName).remove(encryptedKey)
            }
            if (entryMeta.isEmpty()) {
                entryMetasByFileName.remove(fileName)
            }
            if (encryptedKeysByFileName[fileName].isNullOrEmpty()) {
                encryptedKeysByFileName.remove(fileName)
            }
        }
        forcePendingChangesLocked()
    }

    internal companion object {

        @VisibleForTesting
        internal const val BUFFER_CAPACITY = 1024 * 1024 // 1 MiB

        @VisibleForTesting
        internal const val HEADER_SIZE = 8 // 2 bytes (fileName) + 2 bytes (key) + 4 bytes (value)

        @VisibleForTesting
        internal const val FILE_NAME = "safebox_recovery"

        private var instance: SafeBoxRecoveryBlobStore? = null

        @VisibleForTesting
        internal fun create(
            file: File,
            beforeForce: () -> Unit,
        ): SafeBoxRecoveryBlobStore = SafeBoxRecoveryBlobStore(file, beforeForce)

        /**
         * Returns the process-wide singleton recovery store, creating the backing file if needed.
         */
        internal fun getOrCreate(context: Context): SafeBoxRecoveryBlobStore {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val file = File(context.noBackupFilesDir, "$FILE_NAME.bin")
                if (!file.exists()) {
                    file.createNewFile()
                }
                return SafeBoxRecoveryBlobStore(file).also { instance = it }
            }
        }

        internal fun removeInstance() {
            instance = null
        }
    }
}
