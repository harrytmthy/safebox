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

package com.harrytmthy.safebox.keystore

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.SecretKey

/**
 * A memory-safe and destroyable SecretKey implementation.
 *
 * Unlike Java's SecretKeySpec, this does not expose the internal key to GC indefinitely
 * and allows for secure erasure via [destroy].
 */
internal class SafeSecretKey(
    key: ByteArray,
    private val algorithm: String,
) : SecretKey {

    private val buffer: ByteBuffer = ByteBuffer.allocateDirect(key.size).apply {
        put(key)
        flip()
    }

    private var lastCopy: ByteArray? = null

    private var destroyed = false

    init {
        require(key.isNotEmpty()) { "Key must not be empty" }
    }

    /**
     * Zeroes out the most recent clone returned by [getEncoded], and releases the reference.
     * Call this immediately after using the encoded key (e.g. after `cipher.doFinal()`).
     */
    fun releaseHeapCopy() {
        lastCopy?.fill(0)
        lastCopy = null
    }

    /**
     * Returns a clone of the key material stored in direct memory.
     * The returned byte array is cached in [lastCopy] to prevent redundant cloning.
     *
     * Must call [releaseHeapCopy] after using this method to zero the returned key material.
     * This prevents lingering sensitive data in heap.
     */
    override fun getEncoded(): ByteArray? =
        synchronized(buffer) {
            lastCopy?.let { return it }
            val copy = ByteArray(buffer.remaining())
            buffer.mark()
            buffer.get(copy)
            buffer.reset()
            lastCopy = copy
            return copy
        }

    override fun isDestroyed(): Boolean = destroyed

    override fun destroy() {
        synchronized(buffer) {
            releaseHeapCopy()
            for (i in 0 until buffer.capacity()) {
                buffer.put(i, 0.toByte())
            }
            destroyed = true
        }
    }

    override fun getAlgorithm(): String = algorithm

    override fun getFormat(): String? = "RAW"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SecretKey) return false
        if (!algorithm.equals(other.algorithm, ignoreCase = true)) return false
        val thisKey = this.getEncoded() ?: return false
        val otherKey = other.encoded ?: return false
        return MessageDigest.isEqual(thisKey, otherKey)
    }

    override fun hashCode(): Int {
        val encoded = getEncoded() ?: return 0
        return encoded.contentHashCode() xor algorithm.lowercase().hashCode()
    }
}
