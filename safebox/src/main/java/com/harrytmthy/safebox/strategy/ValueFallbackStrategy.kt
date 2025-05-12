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

package com.harrytmthy.safebox.strategy

/**
 * Defines how SafeBox should behave if a value retrieval method is failed, e.g. when
 * `getString()` is called **before** the initial load is completed.
 */
public enum class ValueFallbackStrategy {

    /**
     * Logs a warning but still returns the default value. Useful for observability.
     */
    WARN,

    /**
     * Throws an error immediately when a failure occurs.
     * Recommended during development or debugging to catch issues early.
     */
    ERROR,
}
