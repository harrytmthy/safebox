# Changelog

All notable changes to this project will be documented in this file.

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