/*
 * Copyright 2026 Harry Timothy Tumalewa
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
    alias(libs.plugins.androidx.benchmark)
}

android {
    namespace = "com.harrytmthy.safebox.benchmark"
    testBuildType = "release"

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.output.enable"] = "true"
    }

    buildTypes {
        named("release") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(projects.safebox)
    androidTestImplementation(libs.androidx.benchmark.junit4)
    androidTestImplementation(libs.androidx.security.crypto)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.runner)
}

val measurementFile = providers.gradleProperty("benchmarkMeasurement").orNull?.let {
    rootProject.layout.projectDirectory.file(it).asFile
}
val collectedOutput = layout.buildDirectory.dir(
    "outputs/connected_android_test_additional_output/releaseAndroidTest/connected"
).get().asFile

if (measurementFile != null) {
    require(providers.gradleProperty("android.enableAdditionalTestOutput").orNull == "true") {
        "Pass -Pandroid.enableAdditionalTestOutput=true to collect the device output with Gradle."
    }
    val benchmarkLibrary = providers.gradleProperty("benchmarkLibrary").getOrElse("safebox")
    require(benchmarkLibrary in listOf("safebox", "esp")) {
        "benchmarkLibrary must be safebox or esp."
    }
    android.defaultConfig.testInstrumentationRunnerArguments.apply {
        put("class", "com.harrytmthy.safebox.benchmark.SafeBoxPerformanceTest")
        put("library", benchmarkLibrary)
        put("benchmarkRun", measurementFile.nameWithoutExtension)
    }
    tasks.matching { it.name == "connectedReleaseAndroidTest" }.configureEach {
        val destination = measurementFile
        doFirst {
            check(!destination.exists()) {
                "Measurement already exists: $destination. Choose a new destination."
            }
        }
    }
}

tasks.register("saveMeasurement") {
    val destination = measurementFile
    val sourceDirectory = collectedOutput
    group = "verification"
    description = "Runs the benchmark and saves its original JSON at -PbenchmarkMeasurement."
    if (destination != null) {
        dependsOn("connectedReleaseAndroidTest")
    }
    doLast {
        requireNotNull(destination) {
            "Pass -PbenchmarkMeasurement=docs/benchmarks/measurements/<version>/<filename>.json."
        }
        val reports = sourceDirectory.walkTopDown().filter {
            it.isFile && it.name.endsWith("-benchmarkData.json")
        }.toList()
        check(reports.size == 1) {
            "Expected one benchmark JSON, found ${reports.size}. Select one device with ANDROID_SERIAL."
        }
        destination.parentFile.mkdirs()
        reports.single().copyTo(destination, overwrite = false)
        logger.lifecycle("Measurement saved to $destination")
    }
}
