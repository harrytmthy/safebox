# SafeBox

A secure and performant encrypted storage designed to seamlessly replace the deprecated EncryptedSharedPreferences. SafeBox leverages memory-mapped file storage and binary encryption to ensure high-speed data operations without compromising security.

## Why SafeBox?

While Android provides secured storage APIs like EncryptedSharedPreferences, it introduces significant overhead and performance drawbacks. SafeBox addresses these challenges by providing:
- **Memory-Mapped Speed**: Utilizes memory-mapped I/O for near-instant reads and writes.
- **Binary Blob Encryption**: Data is encrypted and stored in a single binary blob, significantly enhancing performance.
- **Pluggable Encryption Strategy**: Easily configurable encryption and key management mechanisms.
- **No Per-Entry Encryption**: Reduces overhead by avoiding encryption on individual entries, unlike default Jetpack solutions.
- **Lightweight and Modular**: Built for simplicity, scalability, and ease of maintenance.

## Architectural Overview

| Area                 | Tech Used                                |
|----------------------|------------------------------------------|
| Storage Engine       | Memory-Mapped Files (`MappedByteBuffer`) |
| Encryption           | AES (GCM/CBC), AndroidKeyStore           |
| Concurrency          | Kotlin Coroutines                        |
| Integrity (Optional) | HMAC-SHA256                              |
| Serialization        | Binary format                            |
| Testing              | Kotlin Test, Android Instrumentation     |

## System Requirements
- Minimum SDK 23

## Getting Started

Include SafeBox as a module in your Android project and initialize it with your desired configuration:

*(Detailed integration instructions will be provided upon finalizing the public API.)*