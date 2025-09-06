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

import javax.crypto.SecretKey

/**
 * Provides cryptographic keys for SafeBox operations.
 *
 * This interface represents a contract for retrieving or generating secure keys, typically stored
 * in a secure enclave such as AndroidKeyStore.
 */
internal interface KeyProvider {

    /**
     * Returns a [SecretKey] suitable for the intended cryptographic operation.
     *
     * Implementations must guarantee the key is created if it does not already exist.
     *
     * @return A non-null [SecretKey] instance.
     */
    fun getOrCreateKey(): SecretKey
}
