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

plugins {
    alias(libs.plugins.safebox.android.library)
    alias(libs.plugins.safebox.publishing)
    alias(libs.plugins.kotlin.binary.compatibility)
}

android {
    namespace = "com.harrytmthy.safebox"
}

dependencies {
    implementation(projects.safeboxCrypto)
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.security.crypto)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

mavenPublishing {
    pom {
        name.set("SafeBox")
        description.set("A fast and secure replacement for SharedPreferences using memory-mapped file and ChaCha20 encryption.")
    }
}