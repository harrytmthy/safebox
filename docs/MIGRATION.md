# Migrating from EncryptedSharedPreferences

SafeBox is a modern, memory-mapped, ChaCha20-secured replacement for `EncryptedSharedPreferences`, built to offer performance and modularity, with minimal migration effort.

**The best part:** SafeBox implements `SharedPreferences`, so you can drop it in with zero change
to your `putString().commit()` or `getInt()` usage.

## What changes?

### Dagger Setup Example

If you're using **Dagger**:

```kotlin
@Singleton
@Provides
fun provideEncryptedSharedPreferences(context: Context): SharedPreferences {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    return EncryptedSharedPreferences.create(
        context,
        PREF_FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}
```

### To this (SafeBox):

```kotlin
@Singleton
@Provides
fun provideEncryptedSharedPreferences(context: Context): SharedPreferences =
    SafeBox.create(context, PREF_FILE_NAME)
```

No changes to the rest of your code.

### Koin Setup Example

If you're using **Koin**, the migration is just as seamless:

```kotlin
single<SharedPreferences> {
    SafeBox.create(androidContext(), PREF_FILE_NAME)
}
```

## Still unsure?

- SafeBox is open-source and MIT licensed
- Fully tested, memory-safe, and designed for offline-first applications
- Already published to [Maven Central](https://central.sonatype.com/artifact/io.github.harrytmthy-dev/safebox)

Looking to contribute? Found an edge case? Let us know or [open an issue](https://github.com/harrytmthy-dev/safebox/issues).