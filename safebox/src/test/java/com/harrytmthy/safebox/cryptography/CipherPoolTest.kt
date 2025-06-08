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

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CipherPoolTest {

    private lateinit var pool: CipherPool

    @BeforeTest
    fun setup() {
        Security.addProvider(BouncyCastleProvider())
        pool = CipherPool(
            initialSize = 4,
            maxSize = 8,
            loadFactor = 0.75f,
            transformation = ChaCha20CipherProvider.TRANSFORMATION,
            provider = BouncyCastleProvider.PROVIDER_NAME,
        )
    }

    @Test
    fun acquire_and_release_should_work_single_threaded() {
        val cipher = pool.acquire()

        assertNotNull(cipher)
        pool.release(cipher)
    }

    @Test
    fun withCipher_should_provide_cipher_and_return_it() {
        var executed = false

        pool.withCipher { cipher ->
            assertNotNull(cipher)
            executed = true
        }

        assertTrue(executed)
    }

    @Test
    fun cipherPool_should_expand_on_demand() {
        val ciphers = mutableListOf<Cipher>()

        repeat(6) {
            ciphers.add(pool.acquire())
        }

        assertTrue(ciphers.size >= 6)
        ciphers.forEach(pool::release)
    }

    @Test
    fun should_handle_concurrent_access() {
        val threadCount = 16
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        repeat(threadCount) {
            executor.execute {
                try {
                    pool.withCipher { cipher ->
                        assertNotNull(cipher)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        assertTrue(completed)
    }

    @Test(expected = java.util.concurrent.TimeoutException::class)
    fun should_throw_timeout_if_all_busy_and_max_retry_exceeded() {
        val busyPool = CipherPool(
            initialSize = 1,
            maxSize = 1,
            loadFactor = 1f,
            transformation = ChaCha20CipherProvider.TRANSFORMATION,
            provider = BouncyCastleProvider.PROVIDER_NAME,
        )

        val cipher = busyPool.acquire() // Occupy the only one
        try {
            busyPool.acquire() // Should eventually timeout
        } finally {
            busyPool.release(cipher)
        }
    }
}
