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

package com.harrytmthy.safebox.verifier

import com.harrytmthy.safebox.exception.SafeBoxDecryptionException
import javax.crypto.SecretKey

/**
 * Provides data integrity verification for encryption operations that lack built-in authentication,
 * such as AES-CBC. Implementations are responsible for generating and verifying a Message
 * Authentication Code (MAC) to ensure the ciphertext has not been tampered with.
 *
 * This is particularly important when using cipher modes that do not include authentication, since
 * they are vulnerable to bit-flipping and padding oracle attacks without additional protection.
 */
public interface IntegrityVerifier {

    /**
     * Attaches a MAC to the given data using the provided key.
     *
     * @param key The secret key used to generate the MAC.
     * @param data The plaintext or ciphertext to be authenticated.
     * @return A new byte array consisting of [data] followed by the generated MAC tag.
     */
    fun attachMac(key: SecretKey, data: ByteArray): ByteArray

    /**
     * Verifies the MAC attached to the given input and extracts the original data.
     *
     * @param key The secret key used to verify the MAC.
     * @param dataWithMac The combined data and MAC tag.
     * @return The original data with the MAC removed.
     * @throws SafeBoxDecryptionException if verification fails or input is malformed.
     */
    fun verifyMac(key: SecretKey, dataWithMac: ByteArray): ByteArray
}
