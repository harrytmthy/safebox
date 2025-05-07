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

import com.harrytmthy.safebox.exception.SafeBoxDecryptionException
import javax.crypto.SecretKey

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
     * Encrypts the given plaintext using the provided secret key.
     *
     * @param plaintext The raw data to encrypt.
     * @param key The symmetric secret key to use for encryption. Must be an AES key.
     * @return A byte array containing the encrypted ciphertext, including any prepended IV or
     * metadata required for decryption.
     */
    fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray

    /**
     * Decrypts the given ciphertext using the provided secret key.
     *
     * @param ciphertext The previously encrypted data, including any required metadata.
     * @param key The symmetric secret key for decryption. Must match the key used for encryption.
     * @return The original decrypted plaintext.
     * @throws IllegalArgumentException If the key is not supported (e.g. non-AES).
     * @throws SafeBoxDecryptionException If decryption fails due to tampering, corruption, or
     * invalid ciphertext (only for authenticated modes).
     */
    fun decrypt(ciphertext: ByteArray, key: SecretKey): ByteArray
}
