# Migrating from EncryptedSharedPreferences

SafeBox is a modern, memory-mapped, ChaCha20-secured replacement for `EncryptedSharedPreferences`, built to offer performance and modularity, with minimal migration effort.

**The best part:** SafeBox implements `SharedPreferences`, so you can drop it in with zero change
to your `putString().commit()` or `getInt()` usage.

## What changes?

### Setup

If you're using **Dagger**, you only need to change this:

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

to this (SafeBox):

```kotlin
@Singleton
@Provides
fun provideEncryptedSharedPreferences(context: Context): SharedPreferences =
    SafeBox.create(context, PREF_FILE_NAME)
```

Or if you're using **Koin**:

```kotlin
single<SharedPreferences> {
    SafeBox.create(androidContext(), PREF_FILE_NAME)
}
```

### Existing Data Migration

If your app already stores data in `EncryptedSharedPreferences` or even plain `SharedPreferences`, you can migrate them into SafeBox with one line:

```kotlin
SafeBoxMigrationHelper.migrate(from = encryptedPrefs, to = safeBox)
```

✅ This helper is available since version 1.1.0-alpha01.

## Still unsure?

- SafeBox is open-source and MIT licensed
- Fully tested, memory-safe, and designed for offline-first applications
- Already published to [Maven Central](https://central.sonatype.com/artifact/io.github.harrytmthy/safebox)

Looking to contribute? Found an edge case? Let us know or [open an issue](https://github.com/harrytmthy/safebox/issues).