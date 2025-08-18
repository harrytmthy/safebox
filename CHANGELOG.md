# Changelog

All notable changes to this project will be documented in this file.

## [1.1.1] - 2025-08-16

### Fixed
- **ProGuard consumer rules missing**: SafeBox now ships with a default `proguard-consumer-rules.pro` to prevent crashes during R8 shrinking. This fix ensures SafeBox's BouncyCastle usage does not get stripped during release builds. ([#51](https://github.com/harrytmthy/safebox/issues/51))

## [1.1.0] - 2025-06-11

### Added
- **SafeBoxStateManager**: A centralized lifecycle controller that manages `STARTING`, `WRITING`, `IDLE`, and `CLOSED` states per SafeBox instance. It tracks concurrent edits and ensures deterministic closure via `closeWhenIdle()`. ([#17](https://github.com/harrytmthy/safebox/issues/17))
- **SafeBoxGlobalStateObserver**: Observes SafeBox state transitions globally by file name. Useful for debugging or monitoring multiple files. ([#12](https://github.com/harrytmthy/safebox/issues/12))
- **SafeBoxStateListener**: Per-instance listener for tracking lifecycle changes.
- **SafeBoxBlobFileRegistry**: Prevents multiple SafeBox instances from accessing the same file simultaneously, resolving potential file channel conflicts. ([#10](https://github.com/harrytmthy/safebox/issues/10))
- **SafeBoxExecutor**: Internal single-thread executor that supports background crypto operations and is publicly reusable for extensions. ([#30](https://github.com/harrytmthy/safebox/pull/30))
- **CipherPool**: A coroutine-friendly pool for reusing `Cipher` instances across threads. Helps prevent race conditions and improves crypto throughput. ([#25](https://github.com/harrytmthy/safebox/issues/25))
- **SafeBoxMigrationHelper**: Migrate from `EncryptedSharedPreferences` using standard `SharedPreferences` API. ([#13](https://github.com/harrytmthy/safebox/pull/13))

### Changed
- **ChaCha20CipherProvider**: Now backed by `CipherPool` for thread-safe encryption and decryption. ([#25](https://github.com/harrytmthy/safebox/issues/25))
- **SafeSecretKey**: Rewritten for concurrency using short-lived heap caches and reduced synchronized scope. ([#26](https://github.com/harrytmthy/safebox/issues/26))
- **SecureRandomKeyProvider**: Improved concurrency behavior when retrieving or decrypting keys. ([#26](https://github.com/harrytmthy/safebox/issues/26))
- **BouncyCastleProvider**: Lazy-injected only when ChaCha20 isn't available, preserving host app provider configs. ([#1](https://github.com/harrytmthy/safebox/issues/1))
- **compileSdkVersion** bumped to `36`. ([#2](https://github.com/harrytmthy/safebox/issues/2))

### Security
- **XOR-based key masking**: `SafeSecretKey` is now masked in memory using a SHA-256-derived mask. Prevents native memory inspection of raw DEK. ([#23](https://github.com/harrytmthy/safebox/issues/23))
- **On-demand cipher creation**: `AesGcmCipherProvider` no longer holds long-lived `Cipher` instances. ([#28](https://github.com/harrytmthy/safebox/issues/28))

### Docs
- Added **v1.1.0 benchmark results** showing faster performance across `get()`, `put()`, and `commit()` operations. ([#35](https://github.com/harrytmthy/safebox/issues/35))
- Enabled [**GitHub Sponsors**](https://github.com/sponsors/harrytmthy) with the new `Support SafeBox` section. ([#37](https://github.com/harrytmthy/safebox/issues/37))
- Added project metadata badges: Build, License, and Version. ([#39](https://github.com/harrytmthy/safebox/issues/39))

## [1.1.0-rc01] - 2025-06-09

### Added
- **CipherPool**: A coroutine-friendly, thread-safe pool for reusing `Cipher` instances across threads, backed by a load-factor-based expansion strategy. Prevents cryptographic race conditions in read-heavy workloads. ([#25](https://github.com/harrytmthy/safebox/issues/25))
- **SafeBoxExecutor**: Internal singleton executor to support background concurrency operations like CipherPool scaling. Publicly reusable for custom extensions. ([#30](https://github.com/harrytmthy/safebox/pull/30))

### Changed
- **ChaCha20CipherProvider** now uses `CipherPool` for safe concurrent encryption/decryption. ([#25](https://github.com/harrytmthy/safebox/issues/25))
- **SafeSecretKey**: Now supports concurrent access by reducing synchronized scope, caching the unmasked key in a short-lived atomic heap reference. ([#26](https://github.com/harrytmthy/safebox/issues/26))
- **SecureRandomKeyProvider**: Key caching and unmasking now support concurrent access patterns without blocking parallel threads. ([#26](https://github.com/harrytmthy/safebox/issues/26))
- **BouncyCastle provider initialization** is now safer and more flexible: `CipherPool` lazily injects the provider only when ChaCha20 is not available, reducing the risk of overwriting external configurations. ([#1](https://github.com/harrytmthy/safebox/issues/1))
- **compileSdk bumped to 36**: Ensure SafeBox stays forward-compatible with the latest Android APIs. ([#2](https://github.com/harrytmthy/safebox/issues/2))

### Security
- **XOR-based in-memory masking** added to `SafeSecretKey`, preventing runtime memory inspection of the raw DEK. The key is stored in masked form using a SHA-256 hash of the encrypted DEK as its mask. ([#23](https://github.com/harrytmthy/safebox/issues/23))
- **On-demand Cipher creation** for `AesGcmCipherProvider`, eliminating long-lived `Cipher` references that may retain sensitive key material. ([#28](https://github.com/harrytmthy/safebox/issues/28))

## [1.1.0-beta01] - 2025-06-04

### Added
- **SafeBoxStateManager** is now the sole authority over lifecycle states (`STARTING`, `WRITING`, `IDLE`, `CLOSED`). It tracks concurrent edits, coordinates safe apply/commit transitions, and guarantees deterministic closure in `closeWhenIdle()`. ([#17](https://github.com/harrytmthy/safebox/issues/17))
- **Write guard after closure:** Once `SafeBox` transitions to `CLOSED`, all subsequent write operations (`apply()` or `commit()`) are safely blocked. Prevents late `WRITING` emissions and ensures lifecycle integrity. ([#19](https://github.com/harrytmthy/safebox/issues/19))

### Fixed
- GPG signing and secret injection issues in the Maven publish pipeline, resolving deployment failure from alpha02. ([PR #16](https://github.com/harrytmthy/safebox/pull/16))

## [1.1.0-alpha02] - 2025-06-02

### Added
- **SafeBoxBlobFileRegistry** prevents multiple `SafeBox` instances from accessing the same blob file. This enforces a **single-instance-per-file** constraint internally, resolving the risk documented in [#3](https://github.com/harrytmthy/safebox/issues/3). ([#10](https://github.com/harrytmthy/safebox/issues/10))
- **SafeBoxStateListener** for tracking `SafeBox` lifecycle states (`STARTING`, `IDLE`, `WRITING`, `CLOSED`). It can be attached per-instance via `SafeBox.create(...)` or registered globally via `SafeBoxGlobalStateObserver`. ([#12](https://github.com/harrytmthy/safebox/issues/12))
- **SafeBoxGlobalStateObserver** tracks `SafeBox` state transitions by file name, with support for multiple listeners. ([#12](https://github.com/harrytmthy/safebox/issues/12))
- `SafeBox#closeWhenIdle()` defers closure until all pending writes are complete, preventing premature teardown in async environments. ([#12](https://github.com/harrytmthy/safebox/issues/12))

### Behavior Changes
- Calling `SafeBox.create(...)` before closing the existing instance with the same file name now throws `IllegalStateException`.
- Consecutive write operations are now tracked using `MutableStateFlow`, enabling precise notifications of state transitions.

### Docs & Migration
- `README.md` and `MIGRATION.md` updated to reflect the new state registry and observability APIs.
- KDocs improved to clarify the correct usage of `close()` and `closeWhenIdle()`.

## [1.1.0-alpha01] - 2025-05-26

### Added
- `SafeBoxMigrationHelper`: Allows migration from `EncryptedSharedPreferences` to `SafeBox` using `SharedPreferences` API

## [1.0.0] - 2025-05-23

### Added
- 🎉 First stable release published to Maven Central
- `SafeBox.create(...)` API as a drop-in replacement for `EncryptedSharedPreferences`
- Memory-mapped file storage layer for faster I/O performance
- Dual-layer encryption: ChaCha20-Poly1305 for keys & values, AES-GCM key wrapping via `AndroidKeyStore`
- Fully documented public APIs with attached source and Javadoc jars
- Kotlin-first implementation with `SharedPreferences` compatibility
- `MIGRATION.md` guide for easy switch from `EncryptedSharedPreferences`