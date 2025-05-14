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

package com.harrytmthy.safebox.extensions

import com.harrytmthy.safebox.constants.ValueTypeTag
import com.harrytmthy.safebox.storage.Bytes

internal fun ByteArray.toBytes(): Bytes = Bytes(this)

internal fun Int.toEncodedByteArray(valueTypeTag: Byte = ValueTypeTag.INT): ByteArray =
    byteArrayOf(
        valueTypeTag,
        (this shr 24).toByte(),
        (this shr 16).toByte(),
        (this shr 8).toByte(),
        this.toByte(),
    )

internal fun Long.toEncodedByteArray(): ByteArray =
    byteArrayOf(
        ValueTypeTag.LONG,
        (this shr 56).toByte(),
        (this shr 48).toByte(),
        (this shr 40).toByte(),
        (this shr 32).toByte(),
        (this shr 24).toByte(),
        (this shr 16).toByte(),
        (this shr 8).toByte(),
        this.toByte(),
    )

internal fun Float.toEncodedByteArray(): ByteArray =
    this.toRawBits().toEncodedByteArray(ValueTypeTag.FLOAT)

internal fun Boolean.toEncodedByteArray(): ByteArray =
    byteArrayOf(ValueTypeTag.BOOLEAN, if (this) 1 else 0)

internal fun String.toEncodedByteArray(): ByteArray {
    val bytes = toByteArray()
    return byteArrayOf(
        ValueTypeTag.STRING,
        (bytes.size shr 24).toByte(),
        (bytes.size shr 16).toByte(),
        (bytes.size shr 8).toByte(),
        bytes.size.toByte(),
    ) + bytes
}

internal fun Set<String>.toEncodedByteArray(): ByteArray {
    var encoded = byteArrayOf(
        ValueTypeTag.STRING_SET,
        (size shr 24).toByte(),
        (size shr 16).toByte(),
        (size shr 8).toByte(),
        size.toByte(),
    )
    for (string in this) {
        val bytes = string.toByteArray(Charsets.UTF_8)
        encoded += byteArrayOf(
            (bytes.size shr 24).toByte(),
            (bytes.size shr 16).toByte(),
            (bytes.size shr 8).toByte(),
            bytes.size.toByte(),
        ) + bytes
    }
    return encoded
}
