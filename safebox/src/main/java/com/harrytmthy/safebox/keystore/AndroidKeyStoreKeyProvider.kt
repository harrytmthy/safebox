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
import android.security.keystore.KeyProperties.PURPOSE_ENCRYPT
import android.security.keystore.KeyProperties.PURPOSE_SIGN
import com.harrytmthy.safebox.SafeBox.Companion.DEFAULT_VALUE_KEYSTORE_ALIAS
import java.security.KeyStore
import java.security.KeyStore.SecretKeyEntry
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * A flexible [KeyProvider] backed by AndroidKeyStore, capable of provisioning secret keys for
 * various cryptographic purposes (e.g. AES, HMAC).
 *
 * This implementation unifies multiple key strategies into a single reusable provider, where
 * clients define the cryptographic intent via [purposes] and configure key generation parameters
 * using [parameterSpecBuilder].
 *
 * ## Usage Example
 * ```
 * val aesKeyProvider = AndroidKeyStoreKeyProvider(
 *     alias = "aes-key",
 *     purposes = PURPOSE_ENCRYPT or PURPOSE_DECRYPT
 * ) {
 *     setBlockModes("GCM")
 *     setEncryptionPaddings("NoPadding")
 *     setRandomizedEncryptionRequired(false)
 * }
 * ```
 *
 * @param alias The key alias under which the key is stored in AndroidKeyStore.
 * @param purposes The cryptographic purposes (e.g. [PURPOSE_ENCRYPT], [PURPOSE_SIGN]).
 * @param parameterSpecBuilder Lambda that configures the [KeyGenParameterSpec.Builder].
 */
internal class AndroidKeyStoreKeyProvider(
    private val alias: String,
    algorithm: String,
    purposes: Int,
    parameterSpecBuilder: KeyGenParameterSpec.Builder.() -> Unit,
) : KeyProvider {

    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private val defaultKey by lazy {
        if (keyStore.containsAlias(DEFAULT_VALUE_KEYSTORE_ALIAS)) {
            val entry = keyStore.getEntry(DEFAULT_VALUE_KEYSTORE_ALIAS, null) as SecretKeyEntry
            entry.secretKey
        } else {
            val keyGenerator = KeyGenerator.getInstance(algorithm, ANDROID_KEYSTORE)
            keyGenerator.init(
                KeyGenParameterSpec.Builder(DEFAULT_VALUE_KEYSTORE_ALIAS, purposes)
                    .apply(parameterSpecBuilder)
                    .build(),
            )
            keyGenerator.generateKey()
        }
    }

    private val aliasKey by lazy {
        if (keyStore.containsAlias(alias)) {
            val entry = keyStore.getEntry(alias, null) as SecretKeyEntry
            entry.secretKey
        } else {
            defaultKey
        }
    }

    override fun getOrCreateKey(): SecretKey =
        if (keyStore.containsAlias(DEFAULT_VALUE_KEYSTORE_ALIAS)) {
            defaultKey
        } else {
            aliasKey
        }

    override fun rotateKey() {
        defaultKey // Triggers lazy-init
        keyStore.deleteEntry(alias)
    }

    override fun shouldRotateKey(): Boolean {
        val defaultExists = keyStore.containsAlias(DEFAULT_VALUE_KEYSTORE_ALIAS)
        val legacyExists = alias != DEFAULT_VALUE_KEYSTORE_ALIAS && keyStore.containsAlias(alias)
        return !defaultExists || legacyExists
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
