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

package com.harrytmthy.safebox.diagnostics

/**
 * Identifies the failing call. Storage mutations can include their required flushes.
 */
internal enum class FailureKind(val description: String) {
    OPERATION("operation failed"),
    PRIMARY_LOAD("failed to load primary storage"),
    RECOVERY_LOAD("failed to load recovery storage"),
    PRIMARY_WRITE("primary write failed"),
    PRIMARY_REMOVE("failed to remove a primary record"),
    PRIMARY_CLEAR("failed to clear primary storage"),
    PRIMARY_FLUSH("failed to flush primary storage"),
    RECOVERY_WRITE("failed to write a recovery record"),
    RECOVERY_REMOVE("failed to remove a recovery record"),
    RECOVERY_CLEAR("failed to clear recovery storage"),
    RECOVERY_FLUSH("failed to flush recovery storage"),
    DECRYPT_KEY("failed to decrypt a preference key"),
    DECRYPT_VALUE("failed to decrypt a preference value"),
    ENCRYPT_KEY("failed to encrypt a preference key"),
    ENCRYPT_VALUE("failed to encrypt a preference value"),
    INDEX_KEY("failed to index a preference key"),
}
