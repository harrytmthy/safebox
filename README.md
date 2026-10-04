# SafeBox

[![Build](https://img.shields.io/github/actions/workflow/status/harrytmthy/safebox/ci.yml?branch=main&label=build&logo=githubactions&logoColor=white&style=flat-square)](https://github.com/harrytmthy/safebox/actions)
[![License](https://img.shields.io/github/license/harrytmthy/safebox?label=license&color=blue&style=flat-square)](https://github.com/harrytmthy/safebox/blob/main/LICENSE)
[![Release](https://img.shields.io/github/v/release/harrytmthy/safebox?include_prereleases&label=release&color=orange&style=flat-square)](https://github.com/harrytmthy/safebox/releases)

A secure, blazing-fast alternative to `EncryptedSharedPreferences`, designed for Android projects which demand both **speed** and **security**.

## 🚨 EncryptedSharedPreferences is Deprecated

As of **Jetpack Security 1.1.0-alpha07 (April 9, 2025)**, `EncryptedSharedPreferences` is deprecated in favor of platform APIs and direct Android Keystore use. [Google has confirmed](https://developer.android.com/privacy-and-security/cryptography) there will be no subsequent releases of the `security-crypto` library.

SafeBox keeps the `SharedPreferences` API without requiring apps to replace it with a different storage model.

## Why SafeBox?

|                | SafeBox                                              | EncryptedSharedPreferences                   |
|----------------|------------------------------------------------------|----------------------------------------------|
| Encryption     | **Modern ChaCha20-Poly1305 with secure key vault**   | Older AES setup tied to deprecated MasterKey |
| Storage format | **Memory-mapped binary file with minimal headers**   | XML text file with tag/attribute overhead    |
| I/O model      | **New data is tail-appended**                        | New data rewrites the whole XML              |
| Concurrency    | **Stays smooth on concurrent writes**                | Gets slower on concurrent writes             |
| Scalability    | **Stable performance on large size**                 | Keeps getting heavier as data grows          |
| Durability     | **Low-storage failures fallback to a recovery file** | Low-storage failures = data loss             |
| Customization  | **Cipher providers are replaceable**                 | Ciphers are not customizable                 |

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
    implementation("io.github.harrytmthy:safebox:1.4.0")

    // Optional: standalone crypto helper
    implementation("io.github.harrytmthy:safebox-crypto:1.4.0")
}
```

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

> Repeating `SafeBox.create(context, fileName)` returns the existing instance for that `fileName`. When an instance already exists, **all parameters are ignored**, including `failureListener`.

> To observe failures through `FailureListener`, see the [Observability Guide](docs/OBSERVABILITY.md).

## Migrating from EncryptedSharedPreferences

SafeBox is a drop-in replacement for `EncryptedSharedPreferences`.

➡️ [Read the Migration Guide](docs/MIGRATION.md)

## Text Encryption Support

SafeBox includes a simple text encryption helper (`SafeBoxCrypto`) you can use for things like
encrypting values before writing to Room or sending over the network:

```kotlin
// 1. Create a secret and store it in your own vault
val secret: String = SafeBoxCrypto.createSecret()

// 2. Use it to encrypt
val userEntity = UserEntity(
    id = SafeBoxCrypto.encrypt(userId, secret),
    // ...
)

// 3. Retrieve it later
val userId: String = SafeBoxCrypto.decrypt(userEntity.id, secret)
```

If you only need the helper, use the standalone `:safebox-crypto` module.

## Performance Benchmarks

SafeBox performance is measured using Jetpack Microbenchmark on a physical Android device with a non-debuggable release build. Reported figures are median durations over 50 measured samples.

Compared with SafeBox 1.3.0, SafeBox 1.4.0 improves performance across every measured workload:

| Workload          | Speedup vs 1.3.0 |
|-------------------|-----------------:|
| Initialization    |            1.67× |
| Reads             |       1.80–2.59× |
| Commit 1 entry    |            1.30× |
| Bulk commits      |      3.73–18.73× |
| Separate commits  |       1.06–1.31× |

The benchmark suite and raw Jetpack measurement JSON are included in the repository. See the [methodology](benchmark/README.md) and [full benchmark results](docs/benchmarks/RESULTS.md), including the comparison with EncryptedSharedPreferences 1.1.0.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for setup, formatting, testing, and PR guidelines.

## 💖 Support SafeBox

If SafeBox helped secure your app or saved your time, consider sponsoring to support future improvements and maintenance!

[![Sponsor](https://img.shields.io/badge/sponsor-%F0%9F%92%96-blueviolet?style=flat-square)](https://github.com/sponsors/harrytmthy)

## License

```
Apache License 2.0
Copyright (c) 2025 Harry Timothy Tumalewa
```
