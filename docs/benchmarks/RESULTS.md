# Benchmark results

SafeBox 1.4.0 compared with SafeBox 1.3.0 and EncryptedSharedPreferences (ESP) 1.1.0.

## Results

| Workload                | SafeBox 1.4.0 (ms) | SafeBox 1.3.0 (ms) | Speedup vs 1.3.0 | ESP 1.1.0 (ms) | Speedup vs ESP |
|-------------------------|-------------------:|-------------------:|-----------------:|---------------:|---------------:|
| Initialization          |           0.131521 |           0.220213 |            1.67× |      35.926736 |        273.16× |
| Read 1 entry            |           0.004102 |           0.009677 |            2.36× |       0.024775 |          6.04× |
| Read 5 entries          |           0.020481 |           0.049882 |            2.44× |       0.126122 |          6.16× |
| Read 10 entries         |           0.041556 |           0.107596 |            2.59× |       0.316382 |          7.61× |
| Read 50 entries         |           0.199545 |           0.471194 |            2.36× |       1.209949 |          6.06× |
| Read 100 entries        |           0.391005 |           0.703744 |            1.80× |       2.112578 |          5.40× |
| Commit 1 entry          |           0.152591 |           0.197886 |            1.30× |       0.598159 |          3.92× |
| Bulk commit 5 entries   |           0.238233 |           0.888762 |            3.73× |       0.416154 |          1.75× |
| Bulk commit 10 entries  |           0.292417 |           1.812548 |            6.20× |       0.563623 |          1.93× |
| Bulk commit 50 entries  |           0.444568 |           8.326717 |           18.73× |       1.606214 |          3.61× |
| Bulk commit 100 entries |           0.847711 |          15.314375 |           18.07× |       4.925476 |          5.81× |
| 5 separate commits      |           0.769816 |           1.007330 |            1.31× |       1.268337 |          1.65× |
| 10 separate commits     |           1.489802 |           1.848197 |            1.24× |       2.514049 |          1.69× |
| 50 separate commits     |           8.578093 |           9.813128 |            1.14× |      37.130912 |          4.33× |
| 100 separate commits    |          16.403891 |          17.339583 |            1.06× |      71.135339 |          4.34× |

Each workload uses 20 warmup executions followed by 50 measured samples. Median durations are shown in milliseconds.

Measurements were collected on a Samsung SM-S928B running Android 16 / API 36 using a non-debuggable release build.

## Measurement proof

SafeBox 1.4.0: [Jetpack measurement JSON](measurements/safebox-1.4.0/SAFEBOX_1_4_0_MEASUREMENT.json).

SafeBox 1.3.0 uses `io.github.harrytmthy:safebox:1.3.0`: [Jetpack measurement JSON](measurements/safebox-1.3.0/SAFEBOX_1_3_0_MEASUREMENT.json).

ESP uses `androidx.security:security-crypto-ktx:1.1.0`: [Jetpack measurement JSON](measurements/esp-1.1.0/ESP_1_1_0_MEASUREMENT.json).
