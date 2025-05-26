# SafeBox

A secure, blazing-fast alternative to `EncryptedSharedPreferences`, designed for Android projects which demand both **speed** and **security**.

## 🚨 EncryptedSharedPreferences is Deprecated
As of **Jetpack Security 1.1.0-alpha07 (April 9, 2025)**, `EncryptedSharedPreferences` has been deprecated with no official replacement. Without continued support from Google, it may fall behind in cryptography standards, leaving sensitive data exposed.

SafeBox can help you [migrate](docs/MIGRATION.md) easily using the same `SharedPreferences` API.

## Why SafeBox?

| Feature             | SafeBox                           | EncryptedSharedPreferences                |
|---------------------|-----------------------------------|-------------------------------------------|
| Initialization Time | **0.35ms** (*110x faster*)        | 38.7ms                                    |
| Storage Format      | Memory-mapped binary file         | XML-based per-entry                       |
| Encryption Method   | ChaCha20-Poly1305 (keys & values) | AES-SIV for keys, AES-GCM for values      |
| Key Security        | Android Keystore-backed AES-GCM   | Android Keystore MasterKey (*deprecated*) |
| Customization       | Pluggable cipher/key providers    | Tightly coupled                           |

SafeBox uses **deterministic encryption** for reference keys (for fast lookup) and **non-deterministic encryption** for values (for strong security). Both powered by a single ChaCha20 key protected via AES-GCM and stored securely.

### SafeBox Key Derivation & Encryption Flow

```
 [Android Keystore-backed AES-GCM Key]
                  ↓
       [ChaCha20-Poly1305 Key]
              ↙       ↘
    Reference Keys    Entry Values
(deterministic IV)    (randomized IV)
```

Compared to EncryptedSharedPreferences:

```
[Android Keystore MasterKey (deprecated)]
           ↙             ↘
    [AES-SIV Key]    [AES-GCM Key]
         ↓                 ↓
   Reference Keys     Entry Values

```

## Performance Benchmarks

Average times measured over **100 samples** on an emulator:

| Operation                    | SafeBox    | EncryptedSharedPreferences |
|------------------------------|------------|----------------------------|
| Write 1 entry then commit    | **0.55ms** | 1.31ms (*138% slower*)     |
| Read 1 entry                 | **0.39ms** | 0.50ms (*28% slower*)      |
| Write 3 entries then commit  | **1.25ms** | 2.16ms (*73% slower*)      |
| Read 3 entries               | **0.94ms** | 1.27ms (*35% slower*)      |
| Write 5 entries then commit  | **2.33ms** | 3.32ms (*42% slower*)      |
| Read 5 entries               | **1.37ms** | 2.25ms (*64% slower*)      |
| Write 10 entries then commit | **4.73ms** | 6.28ms (*33% slower*)      |
| Read 10 entries              | **3.29ms** | 4.07ms (*24% slower*)      |

Even on **multiple single commits**, SafeBox remains faster:

| Operation                    | SafeBox     | EncryptedSharedPreferences |
|------------------------------|-------------|----------------------------|
| Write and commit 3 entries   | **1.94ms**  | 4.9ms (*152% slower*)      |
| Write and commit 5 entries   | **2.84ms**  | 6.91ms (*143% slower*)     |
| Write and commit 10 entries  | **5.47ms**  | 11.27ms (*106% slower*)    |
| Write and commit 100 entries | **33.19ms** | 71.34ms (*115% slower*)    |

<details>

<summary>View Charts</summary>

![Read Performance](docs/charts/read_performance_chart.png)

![Write Performance](docs/charts/write_performance_chart.png)

![Write then Commit Performance](docs/charts/write_commit_performance_chart.png)

</details>

## Installation

```kotlin
dependencies {
    implementation("io.github.harrytmthy-dev:safebox:1.1.0-alpha01")
}
```

## Basic Usage

```kotlin
val safeBox = SafeBox.create(context, fileName = "secure-prefs")

safeBox.edit()
    .putInt("userId", 123)
    .putString("name", "Luna Moonlight")
    .apply()

val userId = safeBox.getInt("userId", -1)
val email = safeBox.getString("email", null)
```

## Migrating from EncryptedSharedPreferences

SafeBox is a drop-in replacement for `EncryptedSharedPreferences`.

➡️ [Read the Migration Guide](docs/MIGRATION.md)

## Contributing

### Update pre-hook path

`scripts/` contains shared pre-hooks for formatting and test validation. To enable it locally:

```bash
git config --local core.hooksPath scripts
chmod +x scripts/pre-commit
chmod +x scripts/pre-push
```

### Run Spotless

```bash
./gradlew spotlessApply --init-script gradle/init.gradle.kts --no-configuration-cache
```

## License

```
MIT License
Copyright (c) 2025 Harry Timothy Tumalewa
```