# SafeBox

[![Build](https://img.shields.io/github/actions/workflow/status/harrytmthy/safebox/ci.yml?branch=main&label=build&logo=githubactions&logoColor=white&style=flat-square)](https://github.com/harrytmthy/safebox/actions)
[![License](https://img.shields.io/github/license/harrytmthy/safebox?label=license&color=blue&style=flat-square)](https://github.com/harrytmthy/safebox/blob/main/LICENSE)
[![Release](https://img.shields.io/github/v/release/harrytmthy/safebox?include_prereleases&label=release&color=orange&style=flat-square)](https://github.com/harrytmthy/safebox/releases)

A secure, blazing-fast alternative to `EncryptedSharedPreferences`, designed for Android projects which demand both **speed** and **security**.

## 🚨 EncryptedSharedPreferences is Deprecated
As of **Jetpack Security 1.1.0-alpha07 (April 9, 2025)**, `EncryptedSharedPreferences` has been deprecated with no official replacement. Without continued support from Google, it may fall behind in cryptography standards, leaving sensitive data exposed.

SafeBox can help you [migrate](docs/MIGRATION.md) easily using the same `SharedPreferences` API.

## Why SafeBox?

| Feature             | SafeBox v1.1.0                    | EncryptedSharedPreferences                |
|---------------------|-----------------------------------|-------------------------------------------|
| Initialization Time | **0.38ms** (*100x faster*)        | 38.7ms                                    |
| Storage Format      | Memory-mapped binary file         | XML-based per-entry                       |
| Encryption Method   | ChaCha20-Poly1305 (keys & values) | AES-SIV for keys, AES-GCM for values      |
| Key Security        | Android Keystore-backed AES-GCM   | Android Keystore MasterKey (*deprecated*) |
| Customization       | Pluggable cipher providers        | Tightly coupled                           |

SafeBox uses **deterministic encryption** for reference keys (for fast lookup) and **non-deterministic encryption** for values (for strong security). Both powered by a single ChaCha20 key protected via AES-GCM and stored securely.

<details>

<summary>🔑 SafeBox Key Derivation & Encryption Flow</summary>

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

</details>

## Installation

```kotlin
dependencies {
    implementation("io.github.harrytmthy:safebox:1.2.0-alpha01")
}
```

### ⚠️ Heads up

The `io.github.harrytmthy-dev` namespace is now **deprecated**. Starting from `v1.2.0-alpha01`, SafeBox will be published under the canonical Maven group `io.github.harrytmthy`.

Please update your dependencies accordingly.

## Basic Usage

Create the instance:

```kotlin
val prefs: SharedPreferences = SafeBox.create(context, PREF_FILE_NAME)
```

Then use it like any `SharedPreferences`:

```kotlin
prefs.edit()
    .putInt("userId", 123)
    .putString("name", "Luna Moonlight")
    .apply()

val userId = prefs.getInt("userId", -1)
val email = prefs.getString("email", null)
```

Once created, you can retrieve the same instance without a `Context`:

```kotlin
SafeBox.get(PREF_FILE_NAME) // or SafeBox.create(context, PREF_FILE_NAME)
    .edit()
    .clear()
    .commit()
```

> Prefer `SafeBox.getOrNull(fileName)` if you need a safe retrieval without throwing.

### Understanding SafeBox Behavior

SafeBox returns the same instance per filename:

```kotlin
val a1 = SafeBox.create(context, "fileA")
val a2 = SafeBox.create(context, "fileA")
val a3 = SafeBox.get("fileA")

assertTrue(a1 === a2)   // same reference
assertTrue(a1 === a3)   // same reference

val b = SafeBox.create(context, "fileB")
assertTrue(a1 !== b)    // different filenames = different instances
```

> Repeating `SafeBox.create(context, fileName)` returns the existing instance for that `fileName`. When an instance already exists, **all parameters are ignored** except a non-null `stateListener`, which replaces the current listener.

### Observing State Changes

You can observe SafeBox lifecycle state transitions (`STARTING`, `WRITING`, `IDLE`) in two ways.

#### 1. Instance-bound listener

```kotlin
val safeBox = SafeBox.create(
    context = context,
    fileName = PREF_FILE_NAME,
    stateListener = SafeBoxStateListener { state ->
        when (state) {
            SafeBoxState.STARTING -> trackStart()    // Loading from disk
            SafeBoxState.IDLE     -> trackIdle()     // No active persistence
            SafeBoxState.WRITING  -> trackWrite()    // Persisting to disk
        }
    }
)
```

#### 2. Global observer

```kotlin
val listener = SafeBoxStateListener { state ->
    when (state) {
        SafeBoxState.STARTING -> onStart()
        SafeBoxState.IDLE     -> onIdle()
        SafeBoxState.WRITING  -> onWrite()
    }
}
SafeBoxGlobalStateObserver.addListener(PREF_FILE_NAME, listener)

// later
SafeBoxGlobalStateObserver.removeListener(PREF_FILE_NAME, listener)
```

You can also query the current state:

```kotlin
val state = SafeBoxGlobalStateObserver.getCurrentState(PREF_FILE_NAME)
```

## Migrating from EncryptedSharedPreferences

SafeBox is a drop-in replacement for `EncryptedSharedPreferences`.

➡️ [Read the Migration Guide](docs/MIGRATION.md)

## Performance Benchmarks

Average times measured over **100 samples** on an emulator:

<details open>

<summary>📊 v1.1.0 Benchmark</summary>

![Get Performance](docs/charts/v1_1_get_performance_chart.png)

![Put Performance](docs/charts/v1_1_put_performance_chart.png)

![Put then Commit Performance](docs/charts/v1_1_put_and_commit_performance_chart.png)

| Operation                   | SafeBox v1.1.0 | EncryptedSharedPreferences |
|-----------------------------|----------------|----------------------------|
| Initialization              | **0.38ms**     | 38.7ms (*10,079% slower*)  |
| Get 1 entry                 | **0.33ms**     | 0.50ms (*52% slower*)      |
| Get 3 entries               | **0.94ms**     | 1.27ms (*35% slower*)      |
| Get 5 entries               | **1.56ms**     | 2.25ms (*44% slower*)      |
| Get 10 entries              | **3.06ms**     | 4.07ms (*33% slower*)      |
| Put 1 entry, then commit    | **0.49ms**     | 1.31ms (*167% slower*)     |
| Put 3 entries, then commit  | **1.34ms**     | 2.16ms (*61% slower*)      |
| Put 5 entries, then commit  | **2.36ms**     | 3.32ms (*41% slower*)      |
| Put 10 entries, then commit | **4.20ms**     | 6.28ms (*50% slower*)      |

Even on **multiple single commits**, SafeBox remains faster:

| Operation                    | SafeBox v1.1.0 | EncryptedSharedPreferences |
|------------------------------|----------------|----------------------------|
| Commit 3 single entries      | **1.50ms**     | 4.90ms (*227% slower*)     |
| Commit 5 single entries      | **2.39ms**     | 6.91ms (*189% slower*)     |
| Commit 10 single entries     | **5.07ms**     | 11.27ms (*122% slower*)    |
| Commit 100 single entries    | **38.12ms**    | 71.34ms (*87% slower*)     |

</details>

<details>

<summary>📊 v1.0.0 Benchmark</summary>

![Get Performance](docs/charts/read_performance_chart.png)

![Put Performance](docs/charts/write_performance_chart.png)

![Put then Commit Performance](docs/charts/write_commit_performance_chart.png)

| Operation                   | SafeBox v1.0.0 | EncryptedSharedPreferences |
|-----------------------------|----------------|----------------------------|
| Get 1 entry                 | **0.39ms**     | 0.50ms (*28% slower*)      |
| Get 3 entries               | **0.94ms**     | 1.27ms (*35% slower*)      |
| Get 5 entries               | **1.37ms**     | 2.25ms (*64% slower*)      |
| Get 10 entries              | **3.29ms**     | 4.07ms (*24% slower*)      |
| Put 1 entry, then commit    | **0.55ms**     | 1.31ms (*138% slower*)     |
| Put 3 entries, then commit  | **1.25ms**     | 2.16ms (*73% slower*)      |
| Put 5 entries, then commit  | **2.33ms**     | 3.32ms (*42% slower*)      |
| Put 10 entries, then commit | **4.73ms**     | 6.28ms (*33% slower*)      |

Even on **multiple single commits**, SafeBox remains faster:

| Operation                    | SafeBox v1.0.0 | EncryptedSharedPreferences |
|------------------------------|----------------|----------------------------|
| Commit 3 single entries      | **1.94ms**     | 4.90ms (*152% slower*)     |
| Commit 5 single entries      | **2.84ms**     | 6.91ms (*143% slower*)     |
| Commit 10 single entries     | **5.47ms**     | 11.27ms (*106% slower*)    |
| Commit 100 single entries    | **33.19ms**    | 71.34ms (*115% slower*)    |

</details>

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for setup, formatting, testing, and PR guidelines.

## 💖 Support SafeBox

If SafeBox helped secure your app or saved your time, consider sponsoring to support future improvements and maintenance!

[![Sponsor](https://img.shields.io/badge/sponsor-%F0%9F%92%96-blueviolet?style=flat-square)](https://github.com/sponsors/harrytmthy)

## License

```
MIT License
Copyright (c) 2025 Harry Timothy Tumalewa
```