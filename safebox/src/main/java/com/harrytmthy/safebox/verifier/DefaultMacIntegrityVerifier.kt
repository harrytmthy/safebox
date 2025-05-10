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
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Default [IntegrityVerifier] implementation using HMAC-SHA256.
 *
 * This class attaches a MAC tag to the data and verifies it during decryption to ensure integrity.
 * The MAC is computed using a key-derived HMAC and appended to the end of the ciphertext.
 *
 * **Note:**
 * This implementation requires the key to expose its encoded bytes. If used with AndroidKeyStore,
 * ensure the key is exportable, or generate a separate MAC key.
 */
public object DefaultMacIntegrityVerifier : IntegrityVerifier {

    private const val MAC_ALGORITHM = "HmacSHA256"

    private const val MAC_LENGTH = 32

    override fun attachMac(key: SecretKey, data: ByteArray): ByteArray {
        val mac = generateMac(key)
        val tag = mac.doFinal(data)
        return data + tag.copyOf(MAC_LENGTH)
    }

    override fun verifyMac(key: SecretKey, dataWithMac: ByteArray): ByteArray {
        val originalSize = dataWithMac.size - MAC_LENGTH
        if (originalSize <= 0) throw SafeBoxDecryptionException("Invalid MAC input.")

        val data = dataWithMac.copyOfRange(0, originalSize)
        val receivedTag = dataWithMac.copyOfRange(originalSize, dataWithMac.size)
        val mac = generateMac(key)
        val computedTag = mac.doFinal(data).copyOf(MAC_LENGTH)
        if (!receivedTag.contentEquals(computedTag)) {
            throw SafeBoxDecryptionException()
        }
        return data
    }

    private fun generateMac(key: SecretKey): Mac {
        val mac = Mac.getInstance(MAC_ALGORITHM)
        val macKey = SecretKeySpec(key.encoded, MAC_ALGORITHM)
        mac.init(macKey)
        return mac
    }
}
