# SafeBox

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
    implementation("io.github.harrytmthy-dev:safebox:1.1.0-rc01")
}
```

## Basic Usage

First, provide SafeBox as a singleton:

```kotlin
@Singleton
@Provides
fun provideEncryptedSharedPreferences(@ApplicationContext context: Context): SharedPreferences =
    SafeBox.create(context, PREF_FILE_NAME) // Ensuring single instance per file
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

<details>

<summary>⚠️ Anti-Patterns</summary>

#### ❌ Do NOT create multiple SafeBox instances with the same file name before closing the previous one

```kotlin
fun saveUsername(value: String) {
    SafeBox.create(context, PREF_FILE_NAME)
        .edit { putString("username", value) } // ❌ New instance per function call
}
```

This may cause FileChannel conflicts, memory leaks, or stale reads across instances.

---

#### ⚠️ Avoid scoping SafeBox to short-lived components

```kotlin
@Module
@InstallIn(ViewModelComponent::class) // ⚠️ New instance per ViewModel
object SomeModule {
    
    @Provides
    fun provideSafeBox(@ApplicationContext context: Context): SafeBox =
        SafeBox.create(context, PREF_FILE_NAME)
}

class HomeViewModel @Inject constructor(private val safeBox: SafeBox) : ViewModel() {

    override fun onCleared() {
        safeBox.closeWhenIdle() // Technically safe, but why re-create SafeBox for every ViewModel?
    }
}
```

</details>

### Observing State Changes

You can observe SafeBox lifecycle state transitions (`STARTING`, `WRITING`, `IDLE`, `CLOSED`) in two ways:

#### 1. Instance-bound listener

```kotlin
val safeBox = SafeBox.create(
    context = context,
    fileName = PREF_FILE_NAME,
    listener = SafeBoxStateListener { state ->
        when (state) {
            STARTING -> trackStart()    // Loading data into memory
            IDLE     -> trackIdle()     // No active operations
            WRITING  -> trackWrite()    // Writing to disk
            CLOSED   -> trackClose()    // Instance is no longer usable
        }
    }
)
```

#### 2. Global observer

Manually add listeners by file name:

```kotlin
val listener = SafeBoxStateListener { state ->
    when (state) {
        STARTING -> doSomething()   // Loading data into memory
        IDLE     -> doSomething()   // No active operations
        WRITING  -> doSomething()   // Writing to disk
        CLOSED   -> doSomething()   // Instance is no longer usable
    }
}
SafeBoxGlobalStateObserver.addListener(PREF_FILE_NAME, listener)
```

and remove it when it's no longer needed:

```kotlin
SafeBoxGlobalStateObserver.removeListener(PREF_FILE_NAME, listener)
```

You can also query the current state at any time:

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

## License

```
MIT License
Copyright (c) 2025 Harry Timothy Tumalewa
```