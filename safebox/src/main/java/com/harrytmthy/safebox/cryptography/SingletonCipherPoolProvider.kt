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

/**
 * Provides singleton-managed [CipherPool] instances for SafeBox cryptographic providers.
 *
 * This central registry ensures that each supported cipher type (e.g. ChaCha20) reuses
 * a shared pool of `Cipher` instances across SafeBox instances. This avoids redundant
 * memory usage and improves performance under concurrent workloads.
 */
internal object SingletonCipherPoolProvider {

    private val chaCha20CipherPool = CipherPool(
        transformation = ChaCha20CipherProvider.TRANSFORMATION,
        provider = BouncyCastleProvider.PROVIDER_NAME,
    )

    /**
     * Returns a shared [CipherPool] instance.
     */
    fun getChaCha20CipherPool(): CipherPool = chaCha20CipherPool
}
