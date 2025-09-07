# Changelog

All notable changes to this project will be documented in this file.

## [1.3.0-alpha01] - 2025-09-08

### Added
- **Crypto-only module:** New `:safebox-crypto` published as a standalone artifact. ([#110](https://github.com/harrytmthy/safebox/issues/110))
- **SafeBoxCrypto helper:** Simple string-in, string-out ChaCha20-Poly1305 helper with URL-safe Base64. ([#121](https://github.com/harrytmthy/safebox/issues/121))

### Changed
- **Public API cleanup:** Removed alias-based params from public APIs. Creation now manages aliases internally. ([#111](https://github.com/harrytmthy/safebox/issues/111))

### Performance
- **Smaller dependency footprint:** Switched to `bcprov-jdk15on` and replaced blanket keeps with minimal rules. Minified apps are ≈9.5× smaller than before for SafeBox impact. ([#115](https://github.com/harrytmthy/safebox/issues/115))
- **Lower contention:** Isolated ChaCha providers so key and value ciphers do not block each other. ([#117](https://github.com/harrytmthy/safebox/issues/117))

### Fixed
- **R8 errors:** Removed LDAP/X.509 pulls and missing-class warnings while keeping ChaCha20-Poly1305 intact. ([#115](https://github.com/harrytmthy/safebox/issues/115))
- **Single DEK per file:** Ensure both ciphers for a given file resolve the same DEK with per-file locking and lazy load. ([#119](https://github.com/harrytmthy/safebox/issues/119))
- **Listener parity:** Notifications aligned with `SharedPreferences` semantics. ([#102](https://github.com/harrytmthy/safebox/issues/102))

### CI
- **Faster builds:** Remove duplicate Gradle caching and scope concurrency per workflow. ([#108](https://github.com/harrytmthy/safebox/issues/108))

### Breaking changes
- **Alias-based creation removed:** If you previously used a custom `valueKeyStoreAlias`, reads may fail on upgrade. Migrate data to the default alias before adopting this version. ([#103](https://github.com/harrytmthy/safebox/issues/103), [#111](https://github.com/harrytmthy/safebox/issues/111))

## [1.2.0] - 2025-09-01

### Added
- **Getter APIs:** `SafeBox.get(fileName)` and `SafeBox.getOrNull(fileName)` for singleton-style retrieval. ([#64](https://github.com/harrytmthy/safebox/issues/64))

### Changed
- **Namespace update:** Published under `io.github.harrytmthy`. ([#45](https://github.com/harrytmthy/safebox/issues/45))
- **Single instance per filename:** `SafeBox.create(...)` is atomic and idempotent. Repeated calls return the same instance. ([#58](https://github.com/harrytmthy/safebox/issues/58))
- **SharedPreferences parity for reads:** `getXxx()` now blocks until the initial load completes. ([#71](https://github.com/harrytmthy/safebox/issues/71))
- **Centralized runtime orchestration:** New internal `SafeBoxEngine` owns in-memory entries, the initial-load barrier, write sequencing, and AEAD dead-entry purge scheduling. ([#84](https://github.com/harrytmthy/safebox/issues/84), [#82](https://github.com/harrytmthy/safebox/issues/82))
- **Faster engine writes:** Shrinks critical sections and lowers lock contention during bursty apply or commit. ([#92](https://github.com/harrytmthy/safebox/issues/92))
- **Docs refreshed:** README and KDocs updated with v1.2.0 benchmarks. ([#63](https://github.com/harrytmthy/safebox/issues/63), [#89](https://github.com/harrytmthy/safebox/issues/89))

### Fixed
- **AEADBadTagException under concurrency:** ChaCha20-Poly1305 guarded by a process-wide mutex. Removes MAC failures and dead entries during mixed read and write workloads. ([#90](https://github.com/harrytmthy/safebox/issues/90))
- **Persisted clear flag:** Reusing an editor after `clear()` no longer keeps clearing on later commits. ([#86](https://github.com/harrytmthy/safebox/issues/86))
- **Stability carry-overs:** Serialized cryptography and safe purge of unreadable values. ([#72](https://github.com/harrytmthy/safebox/issues/72))

### Deprecated
- **CipherPool and CipherPoolExecutor:** Removal planned in v1.3. ([#78](https://github.com/harrytmthy/safebox/issues/78))
- **AAD-taking factory:** AAD is ignored in v1.2 and removal is planned in v1.3. ([#72](https://github.com/harrytmthy/safebox/issues/72))
- **`setInitialLoadStrategy(...)`:** No-op in v1.2 and removal planned in v1.3. ([#68](https://github.com/harrytmthy/safebox/issues/68))

### Removed
- **SafeBoxStateManager:** Responsibilities moved into `SafeBoxEngine`. ([#84](https://github.com/harrytmthy/safebox/issues/84))
- **SingletonCipherPoolProvider:** Stale internal helper removed. ([#78](https://github.com/harrytmthy/safebox/issues/78))

### Internal
- **CI:** Gradle caching improvements. ([#47](https://github.com/harrytmthy/safebox/issues/47))
- **Rename:** `SafeBoxExecutor` to `CipherPoolExecutor`. ([#49](https://github.com/harrytmthy/safebox/issues/49))

## [1.2.0-rc01] - 2025-08-31

### Fixed
- **Prevent AEADBadTagException under concurrency:** ChaCha20-Poly1305 operations now use a **process-wide mutex** (instead of per-instance locking), eliminating MAC failures and dead entries during mixed read/write workloads. ([#90](https://github.com/harrytmthy/safebox/issues/90))

### Performance
- **Faster write paths:** Shrinks critical update sections and reduces lock contention on bursty `apply()`/`commit()` sequences, improving end-to-end write latency. ([#92](https://github.com/harrytmthy/safebox/issues/92))

### Docs
- **Benchmarks & KDoc refresh:** Updated v1.2.0 benchmark charts & tables in README, and updated KDocs. ([#89](https://github.com/harrytmthy/safebox/issues/89))

## [1.2.0-beta01] - 2025-08-31

### Fixed
- **Persisted cleared flag:** Reusing an editor after `clear()` no longer keeps clearing on later commits. ([#86](https://github.com/harrytmthy/safebox/issues/86))

### Changed
- **Centralized runtime orchestration:** New internal `SafeBoxEngine` now owns in-memory entries, write sequencing, the initial-load barrier, and AEAD dead-entry purge scheduling. ([#84](https://github.com/harrytmthy/safebox/issues/84), [#82](https://github.com/harrytmthy/safebox/issues/82))

### Removed
- **SafeBoxStateManager:** Internal class removed. Its responsibilities moved into `SafeBoxEngine`. ([#84](https://github.com/harrytmthy/safebox/issues/84))

## [1.2.0-alpha02] - 2025-08-29

### Behavior Changes
- **SharedPreferences parity for reads:** `getXxx()` now blocks until the initial load completes. ([#71](https://github.com/harrytmthy/safebox/issues/71))

### Fixed
- **Handle & prevent dead entries:** Carry-over from 1.1.5. Prevents unintentional KEK rotations. When `AEADBadTagException` occurs, SafeBox purges unreadable values safely. ([#72](https://github.com/harrytmthy/safebox/issues/72))

### Deprecated
- **CipherPool** and **CipherPoolExecutor:** Planned for removal in **v1.3**. ([#78](https://github.com/harrytmthy/safebox/issues/78))
- **AAD-taking factory:** AAD is now ignored and planned for removal in **v1.3**. ([#72](https://github.com/harrytmthy/safebox/issues/72))
- **`setInitialLoadStrategy(...)`:** Now a no-op. Planned for removal in **v1.3**. ([#68](https://github.com/harrytmthy/safebox/issues/68))

### Removed
- **SingletonCipherPoolProvider:** Stale internal helper that supported CipherPool. ([#78](https://github.com/harrytmthy/safebox/issues/78))

## [1.2.0-alpha01] - 2025-08-25

### Added
- **Getter APIs**: `SafeBox.get(fileName)` and `SafeBox.getOrNull(fileName)` for singleton-style retrieval. ([#64](https://github.com/harrytmthy/safebox/issues/64))
- **Gradle caching**: Improved CI performance. ([#47](https://github.com/harrytmthy/safebox/issues/47))

### Behavior Changes
- `SafeBox.create(...)` now returns the same instance for a given filename. ([#58](https://github.com/harrytmthy/safebox/issues/58))
- Deprecated `close()` and `closeWhenIdle()`. They are now no-ops. ([#58](https://github.com/harrytmthy/safebox/issues/58))

### Changed
- **Rename** `io.github.harrytmthy-dev` → `io.github.harrytmthy`. ([#45](https://github.com/harrytmthy/safebox/issues/45))
- **Rename** `SafeBoxExecutor` → `CipherPoolExecutor`. ([#49](https://github.com/harrytmthy/safebox/issues/49))

### Docs
- Refreshed KDoc and README. ([#63](https://github.com/harrytmthy/safebox/issues/63))

### Fixed
- Rolls up fixes from 1.1.1–1.1.3, including serialized writes to prevent overlapping `.apply()`/`.commit()`. ([#60](https://github.com/harrytmthy/safebox/issues/60), [#51](https://github.com/harrytmthy/safebox/issues/51), [#54](https://github.com/harrytmthy/safebox/issues/54))

## [1.1.5] - 2025-08-29

### Fixed
- **Handle & prevent dead entries:** Prevent unintentional KEK rotations. When `AEADBadTagException` occurs, SafeBox safely purges unreadable values. ([#72](https://github.com/harrytmthy/safebox/issues/72))

### Deprecated
- **AAD-taking factory:** AAD is now ignored and planned for removal in **v1.3**. ([#72](https://github.com/harrytmthy/safebox/issues/72))

## [1.1.4] - 2025-08-27

### Fixed
- **Serialized cryptography:** Prevents MAC check failure when multiple threads performed crypto concurrently. ([#72](https://github.com/harrytmthy/safebox/issues/72))

## [1.1.3] - 2025-08-24

### Fixed
- **Serialized `.apply()` and `.commit()` writes**: Previously, rapid sequences of `.apply()` and `.commit()` could interleave and cause `AEADBadTagException` or a commit deadlock. SafeBox now enforces strict write sequencing, ensuring only one disk write is active at a time. ([#60](https://github.com/harrytmthy/safebox/issues/60))

## [1.1.2] - 2025-08-19

### Fixed
- **`apply()` now behaves like EncryptedSharedPreferences**: `getXxx()` calls will now return the expected values immediately after `.apply()` is invoked, even before disk writes complete. This resolves issues where values were missing if accessed right after applying edits. ([#54](https://github.com/harrytmthy/safebox/issues/54))

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