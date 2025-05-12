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

package com.harrytmthy.safebox.mode

import android.security.keystore.KeyProperties.BLOCK_MODE_CBC
import android.security.keystore.KeyProperties.BLOCK_MODE_GCM
import android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE
import android.security.keystore.KeyProperties.ENCRYPTION_PADDING_PKCS7
import com.harrytmthy.safebox.verifier.DefaultMacIntegrityVerifier
import com.harrytmthy.safebox.verifier.IntegrityVerifier
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec

/**
 * Represents an AES block mode configuration used in SafeBox encryption. Encapsulates the cipher
 * transformation, padding, IV size, and parameter spec for secure and flexible encryption support.
 */
public sealed class AesMode : BlockMode {

    internal abstract val name: String
    internal abstract val padding: String
    internal abstract val ivSize: Int

    /**
     * The full transformation string, e.g. "AES/GCM/NoPadding".
     */
    internal val transformation: String
        get() = "$ALGORITHM/$name/$padding"

    /**
     * Constructs the appropriate [AlgorithmParameterSpec] for the cipher mode.
     *
     * @param iv The IV used during cipher initialization.
     * @return A valid algorithm parameter spec for the current mode.
     */
    internal abstract fun getParameterSpec(iv: ByteArray): AlgorithmParameterSpec

    /**
     * AES-CBC configuration using PKCS7 padding and 16-byte IV.
     *
     * This mode does not provide built-in authentication, so an [IntegrityVerifier] is required
     * to attach and validate a MAC (Message Authentication Code) for integrity assurance.
     *
     * @param padding The padding scheme to use (default: PKCS7).
     * @param integrityVerifier Computes and verifies message integrity (default: HMAC-SHA256).
     */
    public data class Cbc(
        override val padding: String = ENCRYPTION_PADDING_PKCS7,
        val integrityVerifier: IntegrityVerifier = DefaultMacIntegrityVerifier,
    ) : AesMode() {
        override val name: String = BLOCK_MODE_CBC
        override val ivSize: Int = CBC_IV_SIZE

        override fun getParameterSpec(iv: ByteArray): AlgorithmParameterSpec = IvParameterSpec(iv)
    }

    /**
     * AES-GCM configuration with optional AAD, no padding, and 12-byte IV.
     *
     * @param aad Optional additional authenticated data to bind integrity.
     */
    public data class Gcm(val aad: ByteArray? = null) : AesMode() {
        override val name: String = BLOCK_MODE_GCM
        override val padding: String = ENCRYPTION_PADDING_NONE
        override val ivSize: Int = GCM_IV_SIZE

        override fun getParameterSpec(iv: ByteArray): AlgorithmParameterSpec =
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Gcm
            return aad.contentEquals(other.aad)
        }

        override fun hashCode(): Int = aad?.contentHashCode() ?: 0
    }

    internal companion object {
        const val ALGORITHM = "AES"
        const val CBC_IV_SIZE = 16
        const val GCM_IV_SIZE = 12
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
