/*
 * Copyright 2025 Harry Timothy Tumalewa
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.harrytmthy.safebox.engine

import com.harrytmthy.safebox.cryptography.CipherProvider
import com.harrytmthy.safebox.extensions.toBytes
import com.harrytmthy.safebox.storage.Bytes
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Resolves logical keys without retaining their plaintext or recomputing stored ciphertext.
 * Stored encrypted keys remain the record identifiers used by persistence and recovery.
 */
internal class EntryIndex(private val keyCipherProvider: CipherProvider) {

    private val entries = ConcurrentHashMap<Token, Entry>()

    private val mac = Mac.getInstance("HmacSHA256").apply {
        val secret = ByteArray(32)
        SecureRandom().nextBytes(secret)
        try {
            init(SecretKeySpec(secret, "HmacSHA256"))
        } finally {
            secret.fill(0)
        }
    }

    val size: Int
        get() = entries.size

    operator fun get(key: String): Entry? = entries[token(key)]

    fun lookup(key: String): Lookup {
        val token = token(key)
        return Lookup(token, entries[token])
    }

    fun load(encryptedKey: Bytes, encryptedValue: ByteArray) {
        val key = keyCipherProvider.decrypt(encryptedKey.value).toString(Charsets.UTF_8)
        val token = token(key)
        entries[token] = Entry(token, encryptedKey, encryptedValue)
    }

    fun resolveEncryptedKey(key: String, lookup: Lookup, pendingRecordId: Bytes?): Bytes =
        lookup.entry?.encryptedKey
            ?: pendingRecordId
            ?: keyCipherProvider.encrypt(key.toByteArray(Charsets.UTF_8)).toBytes()

    fun put(lookup: Lookup, encryptedKey: Bytes, encryptedValue: ByteArray) {
        entries[lookup.token] = Entry(lookup.token, encryptedKey, encryptedValue)
    }

    fun remove(lookup: Lookup): Entry? = entries.remove(lookup.token)

    fun remove(entry: Entry): Boolean = entries.remove(entry.token, entry)

    fun clear() = entries.clear()

    fun values(): Collection<Entry> = entries.values

    fun decodeKey(entry: Entry): String =
        keyCipherProvider.decrypt(entry.encryptedKey.value).toString(Charsets.UTF_8)

    private fun token(key: String): Token =
        synchronized(mac) {
            Token(mac.doFinal(key.toByteArray(Charsets.UTF_8)))
        }

    class Lookup internal constructor(internal val token: Token, val entry: Entry?)

    class Entry internal constructor(
        internal val token: Token,
        val encryptedKey: Bytes,
        val encryptedValue: ByteArray,
    )

    class Token internal constructor(private val bytes: ByteArray) {

        private val hashCode = bytes.contentHashCode()

        override fun hashCode(): Int = hashCode

        override fun equals(other: Any?): Boolean =
            this === other || (other is Token && bytes.contentEquals(other.bytes))
    }
}
