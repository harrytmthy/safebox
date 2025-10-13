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

import java.nio.MappedByteBuffer

internal fun MappedByteBuffer.shiftLeft(currentTail: Int, fromOffset: Int, toOffset: Int): Int {
    when {
        fromOffset == toOffset -> return currentTail
        fromOffset < toOffset -> error("Cannot right-shift region!")
    }
    val remainingSize = currentTail - fromOffset
    if (remainingSize == 0) {
        position(toOffset)
    } else {
        position(fromOffset)
        val remainingBytes = ByteArray(remainingSize).apply(::get)
        position(toOffset)
        put(remainingBytes)
    }
    val gap = currentTail - position()
    if (gap > 0) {
        put(ByteArray(gap))
    }
    return currentTail - (fromOffset - toOffset)
}

internal fun MappedByteBuffer.repairCorruptedBytes(startOffset: Int) {
    position(startOffset)
    val zeros = ByteArray(capacity() - startOffset)
    put(zeros)
    force()
}

internal fun HashMap<Bytes, EntryMeta>.adjustOffsets(
    keys: Iterable<Bytes>,
    fromOffset: Int,
    delta: Int,
) {
    if (delta == 0) {
        return
    }
    for (key in keys) {
        val entry = this[key] ?: continue
        if (entry.offset >= fromOffset) {
            this[key] = entry.copy(offset = entry.offset - delta)
        }
    }
}
