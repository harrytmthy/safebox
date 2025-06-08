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

package com.harrytmthy.safebox.cryptography

import com.harrytmthy.safebox.concurrent.SafeBoxExecutor
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher

/**
 * A lightweight, coroutine-friendly object pool for [Cipher] instances.
 *
 * This pool ensures thread-safe and memory-efficient reuse of Cipher objects,
 * avoiding frequent reinitialization costs while preventing race conditions.
 *
 * #### Behavior:
 * - Initializes with [initialSize] Cipher instances, up to [maxSize].
 * - If available Cipher count drops below `currentSize * (1 - loadFactor)`,
 *   it triggers a background refill using [SafeBoxExecutor].
 * - If all instances are in use, [acquire] blocks with exponential backoff until one is available.
 *
 * #### Usage:
 * ```
 * val cipher = cipherPool.acquire()
 * try {
 *     cipher.init(...)
 *     cipher.doFinal(...)
 * } finally {
 *     cipherPool.release(cipher)
 * }
 * ```
 *
 * Or use [withCipher] for safer handling:
 * ```
 * cipherPool.withCipher { cipher ->
 *     cipher.init(...)
 *     cipher.doFinal(...)
 * }
 * ```
 *
 * @param initialSize Initial pool size (default: 8).
 * @param maxSize Maximum pool size (default: 64).
 * @param loadFactor Determines when to refill the pool (default: 0.75).
 * @param transformation Cipher transformation string (e.g. "AES/GCM/NoPadding").
 * @param provider Cipher provider name (e.g. "AndroidOpenSSL").
 *
 * @throws TimeoutException if no Cipher becomes available after max retries.
 */
public class CipherPool @JvmOverloads constructor(
    initialSize: Int = DEFAULT_INITIAL_SIZE,
    maxSize: Int = DEFAULT_MAX_SIZE,
    loadFactor: Float = DEFAULT_LOAD_FACTOR,
    private val transformation: String,
    private val provider: String,
) {

    private val currentSize = AtomicInteger(initialSize.coerceAtMost(DEFAULT_MAX_SIZE))

    private val maxSize: Int = maxSize.coerceAtLeast(initialSize)

    private val loadFactor: Float = loadFactor.coerceAtMost(1f)

    private val pool = ConcurrentLinkedQueue<Cipher>()

    private val loadingMore = AtomicBoolean(false)

    init {
        repeat(currentSize.get()) {
            pool.offer(Cipher.getInstance(transformation, provider))
        }
    }

    /**
     * Acquires a [Cipher] instance from the pool.
     *
     * If none are immediately available, it uses exponential backoff to retry
     * until a Cipher becomes available or the timeout threshold is reached.
     *
     * If the remaining pool size falls below the configured load threshold and the
     * current size is not yet at [maxSize], a background refill will be triggered.
     *
     * @return A reusable [Cipher] instance, ready for use.
     * @throws TimeoutException if no instance is available after max retry delay.
     */
    @Throws(TimeoutException::class)
    public fun acquire(): Cipher {
        val cipher = awaitPollWithExponentialBackoff()
        if (!loadingMore.get()) {
            val currentSize = currentSize.get()
            val loadSize = currentSize * (1f - loadFactor)
            if (pool.size > loadSize || currentSize >= maxSize) {
                return cipher
            }
            loadingMore.set(true)
            SafeBoxExecutor.executeSingleThread {
                for (count in 0 until currentSize) {
                    if (currentSize + count >= maxSize) {
                        break
                    }
                    pool.offer(Cipher.getInstance(transformation, provider))
                    this.currentSize.incrementAndGet()
                }
                loadingMore.set(false)
            }
        }
        return cipher
    }

    /**
     * Releases a previously acquired [Cipher] instance back to the pool.
     *
     * Should always be called after [Cipher] is no longer in use to prevent leaks.
     *
     * @param cipher The [Cipher] instance to be returned to the pool.
     */
    public fun release(cipher: Cipher) {
        pool.offer(cipher)
    }

    /**
     * Safely executes a block of code with a pooled [Cipher] instance.
     *
     * Ensures the instance is returned to the pool after use, even if the block throws.
     *
     * Example usage:
     * ```
     * cipherPool.withCipher { cipher ->
     *     cipher.init(...)
     *     cipher.doFinal(...)
     * }
     * ```
     *
     * @param block The function to run with the [Cipher] instance.
     * @throws TimeoutException if no Cipher is available within the retry limit.
     */
    @Throws(TimeoutException::class)
    public inline fun <T> withCipher(crossinline block: (Cipher) -> T): T {
        val cipher = acquire()
        return try {
            block(cipher)
        } finally {
            release(cipher)
        }
    }

    private fun awaitPollWithExponentialBackoff(): Cipher {
        var cipher = pool.poll()
        if (cipher != null) {
            return cipher
        }
        var retryMillis = RETRY_MILLIS
        while (cipher == null) {
            if (retryMillis > MAX_RETRY_MILLIS) {
                throw TimeoutException("Failed to get Cipher instance.")
            }
            Thread.sleep(retryMillis)
            retryMillis *= 2
            cipher = pool.poll()
        }
        return cipher
    }

    public companion object {
        public const val DEFAULT_INITIAL_SIZE = 8
        public const val DEFAULT_LOAD_FACTOR = 0.75f
        public const val DEFAULT_MAX_SIZE = 64
        internal const val RETRY_MILLIS = 5L
        internal const val MAX_RETRY_MILLIS = 80L
    }
}
