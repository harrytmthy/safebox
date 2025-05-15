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
import com.harrytmthy.safebox.decoder.ByteDecoder
import com.harrytmthy.safebox.strategy.ValueFallbackStrategy
import kotlin.test.Test
import kotlin.test.assertEquals

class ByteExtensionsTest {

    private val fallbackStrategy = ValueFallbackStrategy.WARN

    private val byteDecoder = ByteDecoder(::fallbackStrategy)

    @Test
    fun `Int encode-decode should return original value`() {
        val original = 42

        val encoded = original.toEncodedByteArray()
        val decoded = byteDecoder.decodeInt(encoded)

        assertEquals(ValueTypeTag.INT, encoded.first())
        assertEquals(original, decoded)
    }

    @Test
    fun `Long encode-decode should return original value`() {
        val original = 1234567890123456789L

        val encoded = original.toEncodedByteArray()
        val decoded = byteDecoder.decodeLong(encoded)

        assertEquals(ValueTypeTag.LONG, encoded.first())
        assertEquals(original, decoded)
    }

    @Test
    fun `Float encode-decode should return original value`() {
        val original = 3.14159f
        val encoded = original.toEncodedByteArray()
        val decoded = byteDecoder.decodeFloat(encoded)
        assertEquals(ValueTypeTag.FLOAT, encoded.first())
        assertEquals(original, decoded)
    }

    @Test
    fun `Boolean encode-decode should return true`() {
        val encoded = true.toEncodedByteArray()
        val decoded = byteDecoder.decodeBoolean(encoded)
        assertEquals(ValueTypeTag.BOOLEAN, encoded.first())
        assertEquals(true, decoded)
    }

    @Test
    fun `Boolean encode-decode should return false`() {
        val encoded = false.toEncodedByteArray()
        val decoded = byteDecoder.decodeBoolean(encoded)
        assertEquals(ValueTypeTag.BOOLEAN, encoded.first())
        assertEquals(false, decoded)
    }

    @Test
    fun `String encode-decode should return original value`() {
        val original = "SafeBox is secure!"
        val encoded = original.toEncodedByteArray()
        val decoded = byteDecoder.decodeString(encoded)
        assertEquals(ValueTypeTag.STRING, encoded.first())
        assertEquals(original, decoded)
    }

    @Test
    fun `StringSet encode-decode should return original values`() {
        val original = linkedSetOf("apple", "banana", "cherry")
        val encoded = original.toEncodedByteArray()
        val decoded = byteDecoder.decodeStringSet(encoded)

        assertEquals(ValueTypeTag.STRING_SET, encoded.first())
        assertEquals(original, decoded?.toSet())
    }
}
