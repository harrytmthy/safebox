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

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties.PURPOSE_DECRYPT
import android.security.keystore.KeyProperties.PURPOSE_ENCRYPT
import com.harrytmthy.safebox.mode.AesMode
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * A [KeyStrategy] implementation backed by AndroidKeyStore.
 *
 * This strategy securely generates and retrieves symmetric AES keys tied to a given alias.
 * It configures the key based on the specified [AesMode], ensuring compatibility with the
 * cipher block mode and padding scheme used during encryption.
 *
 * The generated key is hardware-backed (when available), and stored securely inside
 * the AndroidKeyStore system. Keys are persistent across app restarts, but scoped
 * to the device and the given alias.
 *
 * **Note:**
 * - Requires minSdk 23 due to AndroidKeyStore AES support.
 * - Must use a block mode and padding compatible with AndroidKeyStore (e.g. GCM or CBC).
 *
 * @param mode The AES block mode configuration used to generate a compatible key.
 * @param alias The AndroidKeyStore alias under which the key is stored.
 */
public class AndroidKeyStoreStrategy private constructor(
    private val mode: AesMode,
    private val alias: String,
) : KeyStrategy {

    override fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        return if (keyStore.containsAlias(alias)) {
            val entry = keyStore.getEntry(alias, null) as KeyStore.SecretKeyEntry
            entry.secretKey
        } else {
            val keyGenerator = KeyGenerator.getInstance(AesMode.ALGORITHM, ANDROID_KEYSTORE)
            keyGenerator.init(
                KeyGenParameterSpec.Builder(alias, PURPOSE_ENCRYPT or PURPOSE_DECRYPT)
                    .setBlockModes(mode.name)
                    .setEncryptionPaddings(mode.padding)
                    .build(),
            )
            keyGenerator.generateKey()
        }
    }

    public companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        @JvmStatic
        public fun create(mode: AesMode, alias: String): AndroidKeyStoreStrategy = AndroidKeyStoreStrategy(mode, alias)
    }
}
