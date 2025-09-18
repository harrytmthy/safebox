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

package com.harrytmthy.safebox.demo.di

import android.content.Context
import android.content.SharedPreferences
import com.harrytmthy.safebox.SafeBox
import com.harrytmthy.safebox.demo.core.DispatchersProvider
import com.harrytmthy.safebox.demo.core.DispatchersProviderImpl
import com.harrytmthy.safebox.demo.core.ResourcesProvider
import com.harrytmthy.safebox.demo.core.ResourcesProviderImpl
import com.harrytmthy.safebox.demo.data.PlaygroundRepositoryImpl
import com.harrytmthy.safebox.demo.domain.PlaygroundRepository
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
object PlaygroundModule {

    private const val PREF_FILE_NAME = "SafeBoxPlayground"

    @Singleton
    @Provides
    fun provideDispatchersProvider(): DispatchersProvider = DispatchersProviderImpl

    @Singleton
    @Provides
    fun provideEncryptedSharedPreferences(
        context: Context,
        dispatchersProvider: DispatchersProvider,
    ): SharedPreferences =
        SafeBox.create(context, PREF_FILE_NAME, dispatchersProvider.io)

    @Singleton
    @Provides
    fun provideRepository(repository: PlaygroundRepositoryImpl): PlaygroundRepository = repository

    @Singleton
    @Provides
    fun provideResourcesProvider(provider: ResourcesProviderImpl): ResourcesProvider = provider
}
