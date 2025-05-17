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

import android.security.keystore.KeyProperties.BLOCK_MODE_GCM
import android.security.keystore.KeyProperties.DIGEST_SHA256
import android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE
import android.security.keystore.KeyProperties.KEY_ALGORITHM_AES
import android.security.keystore.KeyProperties.KEY_ALGORITHM_HMAC_SHA256
import android.security.keystore.KeyProperties.PURPOSE_DECRYPT
import android.security.keystore.KeyProperties.PURPOSE_ENCRYPT
import android.security.keystore.KeyProperties.PURPOSE_SIGN
import android.security.keystore.KeyProperties.PURPOSE_VERIFY
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class AndroidKeyStoreKeyProviderTest {

    @Test
    fun getOrCreateKey_withAes_shouldReturnValidAesKey() {
        val keyProvider = AndroidKeyStoreKeyProvider(
            alias = "TestAlias",
            algorithm = KEY_ALGORITHM_AES,
            purposes = PURPOSE_ENCRYPT or PURPOSE_DECRYPT,
            parameterSpecBuilder = {
                setBlockModes(BLOCK_MODE_GCM)
                setEncryptionPaddings(ENCRYPTION_PADDING_NONE)
            },
        )

        val key = keyProvider.getOrCreateKey()

        assertEquals("AES", key.algorithm)
    }

    @Test
    fun getOrCreateKey_withAes_shouldReturnTheSameKeyAcrossInvocations() {
        val keyProvider = AndroidKeyStoreKeyProvider(
            alias = "TestAlias",
            algorithm = KEY_ALGORITHM_AES,
            purposes = PURPOSE_ENCRYPT or PURPOSE_DECRYPT,
            parameterSpecBuilder = {
                setBlockModes(BLOCK_MODE_GCM)
                setEncryptionPaddings(ENCRYPTION_PADDING_NONE)
            },
        )

        val first = keyProvider.getOrCreateKey()
        val second = keyProvider.getOrCreateKey()

        assertEquals(first, second)
    }

    @Test
    fun getOrCreateKey_withHmac_shouldReturnValidAesKey() {
        val keyProvider = AndroidKeyStoreKeyProvider(
            alias = "TestAlias",
            algorithm = KEY_ALGORITHM_HMAC_SHA256,
            purposes = PURPOSE_SIGN or PURPOSE_VERIFY,
            parameterSpecBuilder = { setDigests(DIGEST_SHA256) },
        )

        val key = keyProvider.getOrCreateKey()

        assertEquals("AES", key.algorithm)
    }
}
