# Changelog

All notable changes to this project will be documented in this file.

## [1.1.0-alpha01] - 2025-05-26

### Added
- `SafeBoxMigrationHelper`: Allows migration from `EncryptedSharedPreferences` to `SafeBox` using `SharedPreferences` API

## [1.0.0] - 2025-05-23

### Added
- 🎉 First stable release published to Maven Central
- `SafeBox.create(...)` API as a drop-in replacement for `EncryptedSharedPreferences`
- Memory-mapped file storage layer for faster I/O performance
- Dual-layer encryption: ChaCha20-Poly1305 for keys & values, AES-GCM key wrapping via AndroidKeyStore
- Fully documented public APIs with attached source and Javadoc jars
- Kotlin-first implementation with SharedPreferences compatibility
- `MIGRATION.md` guide for easy switch from `EncryptedSharedPreferences`