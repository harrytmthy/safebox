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

package com.harrytmthy.safebox

import android.content.Context
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * SafeBoxProvider is a singleton holder for a [SafeBox] instance.
 * You must call [init] before calling [get], usually in your Application class.
 */
object SafeBoxProvider {

    @Volatile
    private var instance: SafeBox? = null

    /**
     * Initializes the singleton SafeBox instance.
     *
     * @param context Application context
     * @param fileName Name of the file used for storage
     * @param keyAlias Alias for key encryption
     * @param valueKeyStoreAlias Alias for value encryption using Keystore
     * @param aad Additional authenticated data for AES-GCM (defaults to fileName)
     */
    @JvmStatic
    @Synchronized
    fun init(
        context: Context,
        fileName: String,
        keyAlias: String = SafeBox.DEFAULT_KEY_ALIAS,
        valueKeyStoreAlias: String = SafeBox.DEFAULT_VALUE_KEYSTORE_ALIAS,
        aad: ByteArray = fileName.toByteArray(),
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) {
        if (instance == null) {
            instance = SafeBox.create(
                context.applicationContext,
                fileName,
                keyAlias,
                valueKeyStoreAlias,
                aad,
                ioDispatcher,
            )
        }
    }

    /**
     * Returns the initialized SafeBox instance.
     *
     * @throws IllegalStateException if called before [init]
     */
    @JvmStatic
    fun get(): SafeBox {
        return instance ?: throw IllegalStateException(
            "SafeBoxProvider is not initialized. Call SafeBoxProvider.init(context, fileName) in your Application class.",
        )
    }

    /**
     * Used only for testing to forcibly reset the instance.
     */
    @VisibleForTesting
    @JvmStatic
    fun reset() {
        instance = null
    }
}
