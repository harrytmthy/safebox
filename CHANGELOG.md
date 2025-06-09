# Changelog

All notable changes to this project will be documented in this file.

## [1.1.0-rc01] - 2025-06-09

### Added
- **CipherPool**: A coroutine-friendly, thread-safe pool for reusing `Cipher` instances across threads, backed by a load-factor-based expansion strategy. Prevents cryptographic race conditions in read-heavy workloads. ([#25](https://github.com/harrytmthy-dev/safebox/issues/25))
- **SafeBoxExecutor**: Internal singleton executor to support background concurrency operations like CipherPool scaling. Publicly reusable for custom extensions. ([#30](https://github.com/harrytmthy-dev/safebox/pull/30))

### Changed
- **ChaCha20CipherProvider** now uses `CipherPool` for safe concurrent encryption/decryption. ([#25](https://github.com/harrytmthy-dev/safebox/issues/25))
- **SafeSecretKey**: Now supports concurrent access by reducing synchronized scope, caching the unmasked key in a short-lived atomic heap reference. ([#26](https://github.com/harrytmthy-dev/safebox/issues/26))
- **SecureRandomKeyProvider**: Key caching and unmasking now support concurrent access patterns without blocking parallel threads. ([#26](https://github.com/harrytmthy-dev/safebox/issues/26))
- **BouncyCastle provider initialization** is now safer and more flexible: `CipherPool` lazily injects the provider only when ChaCha20 is not available, reducing the risk of overwriting external configurations. ([#1](https://github.com/harrytmthy-dev/safebox/issues/1))
- **compileSdk bumped to 36**: Ensure SafeBox stays forward-compatible with the latest Android APIs. ([#2](https://github.com/harrytmthy-dev/safebox/issues/2))

### Security
- **XOR-based in-memory masking** added to `SafeSecretKey`, preventing runtime memory inspection of the raw DEK. The key is stored in masked form using a SHA-256 hash of the encrypted DEK as its mask. ([#23](https://github.com/harrytmthy-dev/safebox/issues/23))
- **On-demand Cipher creation** for `AesGcmCipherProvider`, eliminating long-lived `Cipher` references that may retain sensitive key material. ([#28](https://github.com/harrytmthy-dev/safebox/issues/28))

## [1.1.0-beta01] - 2025-06-04

### Added
- **SafeBoxStateManager** is now the sole authority over lifecycle states (`STARTING`, `WRITING`, `IDLE`, `CLOSED`). It tracks concurrent edits, coordinates safe apply/commit transitions, and guarantees deterministic closure in `closeWhenIdle()`. ([#17](https://github.com/harrytmthy-dev/safebox/issues/17))
- **Write guard after closure:** Once `SafeBox` transitions to `CLOSED`, all subsequent write operations (`apply()` or `commit()`) are safely blocked. Prevents late `WRITING` emissions and ensures lifecycle integrity. ([#19](https://github.com/harrytmthy-dev/safebox/issues/19))

### Fixed
- GPG signing and secret injection issues in the Maven publish pipeline, resolving deployment failure from alpha02. ([PR #16](https://github.com/harrytmthy-dev/safebox/pull/16))

## [1.1.0-alpha02] - 2025-06-02

### Added
- **SafeBoxBlobFileRegistry** prevents multiple `SafeBox` instances from accessing the same blob file. This enforces a **single-instance-per-file** constraint internally, resolving the risk documented in [#3](https://github.com/harrytmthy-dev/safebox/issues/3). ([#10](https://github.com/harrytmthy-dev/safebox/issues/10))
- **SafeBoxStateListener** for tracking `SafeBox` lifecycle states (`STARTING`, `IDLE`, `WRITING`, `CLOSED`). It can be attached per-instance via `SafeBox.create(...)` or registered globally via `SafeBoxGlobalStateObserver`. ([#12](https://github.com/harrytmthy-dev/safebox/issues/12))
- **SafeBoxGlobalStateObserver** tracks `SafeBox` state transitions by file name, with support for multiple listeners. ([#12](https://github.com/harrytmthy-dev/safebox/issues/12))
- `SafeBox#closeWhenIdle()` defers closure until all pending writes are complete, preventing premature teardown in async environments. ([#12](https://github.com/harrytmthy-dev/safebox/issues/12))

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