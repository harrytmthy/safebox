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

/**
 * Defines a pluggable encryption and decryption contract for use within SafeBox.
 *
 * Implementations must provide symmetric encryption using a shared secret key.
 * Ciphertext integrity and authentication depend on the specific algorithm used
 * by the implementation (e.g. AES-GCM provides built-in authentication, AES-CBC does not).
 *
 * This interface is intentionally minimal to allow interoperability with various
 * cipher modes and key management strategies.
 */
public interface CipherProvider {

    /**
     * Encrypts the given [plaintext] using the provided secret key.
     */
    fun encrypt(plaintext: ByteArray): ByteArray

    /**
     * Decrypts the given [ciphertext] using the provided secret key.
     */
    fun decrypt(ciphertext: ByteArray): ByteArray

    /**
     * Triggers key rotation by replacing the existing key with a new one.
     */
    fun rotateKey() = Unit

    /**
     * Decides whether or not key rotation must be done.
     *
     * @return `true` if key rotation must be done. `false` otherwise.
     */
    fun shouldRotateKey(): Boolean = false

    /**
     * Destroys any associated cryptographic key material. This method is called when SafeBox
     * is permanently closed to prevent key leakage from memory.
     *
     * Default implementation is a no-op.
     */
    fun destroyKey() = Unit
}
