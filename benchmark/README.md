# SafeBox benchmarks

Measures SafeBox or EncryptedSharedPreferences in separate runs, with 15 workloads per run.

See [results](../docs/benchmarks/RESULTS.md) for the aggregated measurements.

| Operation                            | Entry counts      |
|--------------------------------------|-------------------|
| Initialization                       | N/A               |
| Read N entries                       | 1, 5, 10, 50, 100 |
| Commit 1 entry                       | 1                 |
| Put N entries, then commit once      | 5, 10, 50, 100    |
| Put and commit each entry separately | 5, 10, 50, 100    |

## Methodology

Jetpack Microbenchmark 1.4.1 runs in a release APK with `debuggable=false`. Each workload uses 20 warmup executions and 50 measured samples. Participants use the same workloads and configuration, with one complete dataset per participant.

Each timing sample averages repeated workload executions. Reported durations are medians of these sample averages. Speedup is `baseline median / current SafeBox median`. Ratios above 1× mean shorter durations for the current SafeBox version.

SafeBox 1.3.0 and EncryptedSharedPreferences 1.1.0 are fixed baselines. Future SafeBox versions reuse their stored measurements and compare against each baseline.

Initialization measures repeated creation using the same filename and normal library caches within one process, following an initial creation. It does not measure process-cold startup. Reads use populated integer entries. Bulk commit performs N puts followed by one commit. Separate commits perform N individual put-and-commit operations. Cleanup is excluded from timing.

Measurements use a Samsung SM-S928B running Android 16 / API 36, connected by USB with the screen awake.

## Build

The default dependency is the current SafeBox project. To measure a published release, use `implementation("io.github.harrytmthy:safebox:1.3.0")` in place of `implementation(projects.safebox)` in `benchmark/build.gradle.kts` for that build. Restore the project dependency before building the current version. The library version is selected at build time.

```shell
./gradlew :benchmark:assembleReleaseAndroidTest
```

## Run

Replace `SERIAL` with the physical device serial. Keep the device awake and unused during the run.

```shell
ANDROID_SERIAL=SERIAL ./gradlew :benchmark:saveMeasurement \
  -Pandroid.enableAdditionalTestOutput=true \
  -PbenchmarkLibrary=safebox \
  -PbenchmarkMeasurement=docs/benchmarks/measurements/safebox-1.4.0/SAFEBOX_1_4_0_MEASUREMENT.json
```

Gradle builds and installs the selected release APK, runs all 15 workloads, then saves the original Jetpack JSON at the specified path relative to the project root. The destination labels the measurement. It does not select the library version, which must match the dependency chosen above. Existing measurement files are never overwritten. Use a new destination for a new measurement.

Use `-PbenchmarkLibrary=esp` to measure EncryptedSharedPreferences, with its own measurement path. `benchmarkLibrary` defaults to `safebox`.

The Benchmark Gradle plugin requests ART ahead-of-time compilation with the `speed` compiler filter before measurement, equivalent to `adb shell cmd package compile -m speed -f PACKAGE`. This is separate from `debuggable=false`, which disables debugging. See [Android's compilation documentation](https://developer.android.com/topic/performance/memory/guide/app-code).

Android first writes the output on the device. Gradle collects the JSON and traces into `benchmark/build/outputs/connected_android_test_additional_output/`. The `saveMeasurement` task copies only the JSON into the requested destination after a successful run. It requires exactly one JSON, so select one device with `ANDROID_SERIAL`. No manual download or JSON editing is needed. See [Android's benchmark output documentation](https://developer.android.com/topic/performance/benchmarking/benchmarking-in-ci).
