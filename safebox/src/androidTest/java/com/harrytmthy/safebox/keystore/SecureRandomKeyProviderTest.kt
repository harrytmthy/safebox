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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harrytmthy.safebox.cryptography.AesGcmCipherProvider
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import kotlin.test.Test
import kotlin.test.assertContentEquals

@RunWith(AndroidJUnit4::class)
class SecureRandomKeyProviderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var provider: SecureRandomKeyProvider

    @Before
    fun setUp() {
        val cipher = AesGcmCipherProvider.create("test".toByteArray())
        provider = SecureRandomKeyProvider.create(
            context = context,
            fileName = "test_key",
            keySize = 32,
            algorithm = "ChaCha20",
            cipherProvider = cipher,
        )
    }

    @After
    fun tearDown() {
        File(context.noBackupFilesDir, "test_key.bin").delete()
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry("test-gcm-alias")
        }
    }

    @Test
    fun getOrCreateKey_shouldReturnSameKey() {
        val first = provider.getOrCreateKey()
        val second = provider.getOrCreateKey()
        assertContentEquals(first.encoded, second.encoded)
    }

    @Test
    fun keyFile_shouldPersistAcrossProviderInstances() {
        val original = provider.getOrCreateKey().encoded
        val newInstance = SecureRandomKeyProvider.create(
            context = context,
            fileName = "test_key",
            keySize = 32,
            algorithm = "ChaCha20",
            cipherProvider = AesGcmCipherProvider.create("test".toByteArray()),
        )
        val reloaded = newInstance.getOrCreateKey().encoded
        assertContentEquals(original, reloaded)
    }
}
