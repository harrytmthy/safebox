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
}

android {
    namespace = "com.harrytmthy.safebox.cryptography"

    defaultConfig {
        consumerProguardFiles("proguard-consumer-rules.pro")
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.bouncy.castle.provider)

    testImplementation(libs.kotlin.test)

    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlin.test)
}

mavenPublishing {
    pom {
        name.set("SafeBox Crypto")
        description.set("Cryptography core used by SafeBox, including ChaCha20-Poly1305 and AES-GCM keystore wrapping.")
    }
}