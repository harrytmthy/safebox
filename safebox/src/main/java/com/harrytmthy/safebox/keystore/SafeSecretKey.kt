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
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.SecretKey

/**
 * A memory-safe and destroyable [SecretKey] implementation with in-memory masking.
 *
 * Unlike Java's SecretKeySpec, this class stores the key material in a masked form inside a
 * [ByteBuffer] allocated off-heap.
 *
 * The provided [mask] is used to XOR the key before storage and to reconstruct it on demand.
 * This adds a layer of obfuscation in memory, making it significantly harder for attackers
 * to retrieve the original key through memory inspection.
 *
 * The unmasked key is only briefly reconstructed when [getEncoded] is called and should be
 * securely wiped using [releaseHeapCopy] immediately after use (e.g. after `cipher.doFinal()`).
 *
 * Call [destroy] to securely zero out both masked key and mask from memory when no longer needed.
 */
internal class SafeSecretKey(
    key: ByteArray,
    mask: ByteArray,
    private val algorithm: String,
) : SecretKey {

    private val keyBuffer: ByteBuffer = ByteBuffer.allocateDirect(key.size).apply {
        put(key.xorInPlace(mask))
        flip()
    }

    private val maskBuffer: ByteBuffer = ByteBuffer.allocateDirect(mask.size).apply {
        put(mask)
        flip()
    }

    private var lastCopy: ByteArray? = null

    private val destroyed = AtomicBoolean(false)

    init {
        require(key.isNotEmpty()) { "Key must not be empty" }
        require(mask.isNotEmpty()) { "Mask must not be empty" }
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
     * Returns the original key material by unmasking the value stored in direct memory.
     *
     * Internally, the masked key is stored in [keyBuffer] (masked with [maskBuffer]).
     * This method reconstructs the original key using XOR, then caches it in [lastCopy]
     * to avoid redundant unmasking on repeated calls.
     *
     * ⚠️ Must call [releaseHeapCopy] after using the returned key. This securely zeroes out
     * the temporary key material in heap, preventing it from lingering in memory.
     */
    override fun getEncoded(): ByteArray? =
        synchronized(keyBuffer) {
            lastCopy?.let { return it.xor(maskBuffer.toByteArray()) }
            val copy = keyBuffer.toByteArray()
            lastCopy = copy
            return copy.xor(maskBuffer.toByteArray())
        }

    override fun isDestroyed(): Boolean = destroyed.get()

    override fun destroy() {
        synchronized(keyBuffer) {
            releaseHeapCopy()
            for (i in 0 until keyBuffer.capacity()) {
                maskBuffer.put(i, 0.toByte())
                keyBuffer.put(i, 0.toByte())
            }
            destroyed.set(true)
        }
    }

    /**
     * A ByteArray XOR operation, truncating to the smaller size.
     */
    private fun ByteArray.xor(value: ByteArray): ByteArray {
        val size = this.size.coerceAtMost(value.size)
        return ByteArray(size) { index ->
            (this[index].toInt() xor value[index].toInt()).toByte()
        }
    }

    /**
     * A ByteArray in-place XOR operation for efficiency, truncating to the smaller size.
     */
    private fun ByteArray.xorInPlace(value: ByteArray): ByteArray {
        val size = this.size.coerceAtMost(value.size)
        for (index in 0 until size) {
            this[index] = (this[index].toInt() xor value[index].toInt()).toByte()
        }
        return this
    }

    private fun ByteBuffer.toByteArray(): ByteArray =
        ByteArray(this.remaining()).apply {
            mark()
            get(this)
            reset()
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
