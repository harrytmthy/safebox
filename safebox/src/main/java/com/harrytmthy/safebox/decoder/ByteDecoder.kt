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

package com.harrytmthy.safebox.decoder

import android.util.Log
import com.harrytmthy.safebox.constants.ValueTypeTag
import com.harrytmthy.safebox.strategy.ValueFallbackStrategy
import com.harrytmthy.safebox.strategy.ValueFallbackStrategy.ERROR
import com.harrytmthy.safebox.strategy.ValueFallbackStrategy.WARN
import java.util.concurrent.atomic.AtomicReference

internal class ByteDecoder {

    private val castFailureStrategy = AtomicReference(WARN)

    fun setCastFailureStrategy(fallbackStrategy: ValueFallbackStrategy) {
        castFailureStrategy.set(fallbackStrategy)
    }

    fun decodeAny(src: ByteArray): Any? =
        when (src.firstOrNull()) {
            ValueTypeTag.INT -> decodeInt(src)
            ValueTypeTag.LONG -> decodeLong(src)
            ValueTypeTag.FLOAT -> decodeFloat(src)
            ValueTypeTag.BOOLEAN -> decodeBoolean(src)
            ValueTypeTag.STRING -> decodeString(src)
            ValueTypeTag.STRING_SET -> decodeStringSet(src)
            else -> {
                when (castFailureStrategy.get()) {
                    ERROR -> error("Unknown or missing type tag: ${src.firstOrNull()}")
                    WARN -> Log.w("SafeBox", "Unknown or missing type tag: ${src.firstOrNull()}")
                }
                null
            }
        }

    fun decodeInt(src: ByteArray, valueTypeTag: Byte = ValueTypeTag.INT): Int? {
        if (!src.checkType(valueTypeTag)) {
            return null
        }
        return (src[1].toInt() and 0xFF shl 24) or
            (src[2].toInt() and 0xFF shl 16) or
            (src[3].toInt() and 0xFF shl 8) or
            (src[4].toInt() and 0xFF)
    }

    fun decodeLong(src: ByteArray): Long? {
        if (!src.checkType(ValueTypeTag.LONG)) {
            return null
        }
        return (src[1].toLong() and 0xFF shl 56) or
            (src[2].toLong() and 0xFF shl 48) or
            (src[3].toLong() and 0xFF shl 40) or
            (src[4].toLong() and 0xFF shl 32) or
            (src[5].toLong() and 0xFF shl 24) or
            (src[6].toLong() and 0xFF shl 16) or
            (src[7].toLong() and 0xFF shl 8) or
            (src[8].toLong() and 0xFF)
    }

    fun decodeFloat(src: ByteArray): Float? =
        decodeInt(src, ValueTypeTag.FLOAT)?.let(Float::fromBits)

    fun decodeBoolean(src: ByteArray): Boolean? {
        if (!src.checkType(ValueTypeTag.BOOLEAN)) {
            return null
        }
        return src[1].toInt() != 0
    }

    fun decodeString(src: ByteArray): String? {
        if (!src.checkType(ValueTypeTag.STRING)) {
            return null
        }
        val length = (src[1].toInt() and 0xFF shl 24) or
            (src[2].toInt() and 0xFF shl 16) or
            (src[3].toInt() and 0xFF shl 8) or
            (src[4].toInt() and 0xFF)
        return String(src, 5, length, Charsets.UTF_8)
    }

    fun decodeStringSet(src: ByteArray): Set<String>? {
        if (!src.checkType(ValueTypeTag.STRING_SET)) return null
        var index = 1
        val totalCount = (src[index++].toInt() and 0xFF shl 24) or
            (src[index++].toInt() and 0xFF shl 16) or
            (src[index++].toInt() and 0xFF shl 8) or
            (src[index++].toInt() and 0xFF)
        val result = LinkedHashSet<String>()
        repeat(totalCount) {
            val length = (src[index++].toInt() and 0xFF shl 24) or
                (src[index++].toInt() and 0xFF shl 16) or
                (src[index++].toInt() and 0xFF shl 8) or
                (src[index++].toInt() and 0xFF)
            result += String(src, index, length, Charsets.UTF_8)
            index += length
        }
        return result
    }

    private fun ByteArray.checkType(expectedTypeTag: Byte): Boolean {
        val typeTag = this.firstOrNull()
        if (typeTag != expectedTypeTag) {
            when (castFailureStrategy.get()) {
                ERROR -> error("Expected tag $expectedTypeTag, but $typeTag was found.")
                WARN -> Log.w("SafeBox", "Expected tag $expectedTypeTag, but $typeTag was found.")
            }
            return false
        }
        return true
    }
}
