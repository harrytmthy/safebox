# SafeBox benchmarks

Measures SafeBox and EncryptedSharedPreferences (ESP) using Jetpack Microbenchmark. See the [benchmark results](../docs/benchmarks/RESULTS.md).

| Workload                             | Entry counts      |
|--------------------------------------|-------------------|
| Initialization                       | N/A               |
| Read N entries                       | 1, 5, 10, 50, 100 |
| Commit 1 entry                       | 1                 |
| Put N entries, then commit once      | 5, 10, 50, 100    |
| Put and commit each entry separately | 5, 10, 50, 100    |

## Methodology

- Benchmarks run in a release APK with `debuggable=false` and ART `speed` compilation.

- Measurements are conducted using a physical device with Airplane Mode + DND enabled, with Background process limit set to `No background processes`.

- Initialization measures warm paths instead of process-cold startup.

- Speedup is calculated as:
  <br>
  ```text
  baseline median / current SafeBox median
  ```

## Run

To measure a published SafeBox release instead of the current project, temporarily replace the SafeBox project dependency in `benchmark/build.gradle.kts` with the corresponding Maven artifact.

Run the benchmark on one physical device:

```shell
ANDROID_SERIAL=SERIAL ./gradlew :benchmark:saveMeasurement \
  -Pandroid.enableAdditionalTestOutput=true \
  -PbenchmarkLibrary=safebox \
  -PbenchmarkMeasurement=docs/benchmarks/measurements/safebox-1.4.0/SAFEBOX_1_4_0_MEASUREMENT.json
```

### Explanations

- `benchmarkLibrary` accepts `safebox` or `esp`. It defaults to `safebox`.

- `benchmarkMeasurement` selects where the original Jetpack JSON is saved. It does not select the library version.

- Gradle collects benchmark output under:
  <br>
  ```text
  benchmark/build/outputs/connected_android_test_additional_output/
  ```

- `saveMeasurement` copies the original benchmark JSON into the requested destination after a successful run. Existing measurement files are never overwritten.

- Profiling traces referenced by the JSON are not versioned because of their size. The measurement JSON is preserved unchanged.
