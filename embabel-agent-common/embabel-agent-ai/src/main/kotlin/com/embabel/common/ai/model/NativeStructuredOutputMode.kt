/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.common.ai.model

import com.embabel.common.ai.model.spi.InternalExtensionApi

/**
 * Extension key for native structured-output overrides in [LlmOptions.extensions].
 */
const val NATIVE_STRUCTURED_OUTPUT_EXTENSION = "native.structuredOutput"

/**
 * Per-call native structured-output mode.
 *
 * This is intentionally small and additive: it lets callers override the runtime
 * decision without changing the public [LlmOptions] data shape.
 */
enum class NativeStructuredOutputMode {
    /**
     * Check schema compatibility before using the native path.
     * If the schema is not conservatively compatible, fall back to prompt-based extraction
     * and log a WARN. Use [DISABLED] to opt out of native output entirely (also suppresses the warning).
     */
    DEFAULT,

    /**
     * Always use the native structured-output path when the provider supports it,
     * bypassing the schema compatibility check. The schema must still be valid for the provider.
     */
    ENABLED,

    /**
     * Always use prompt-based extraction; never use the native path.
     */
    DISABLED;

    /**
     * Apply this native structured-output mode to [options].
     */
    fun applyTo(options: LlmOptions): LlmOptions = options.withNativeStructuredOutput(this)
}

/**
 * Attach a native structured-output mode to [LlmOptions].
 */
@OptIn(InternalExtensionApi::class)
@JvmName("withNativeStructuredOutput")
fun LlmOptions.withNativeStructuredOutput(mode: NativeStructuredOutputMode): LlmOptions =
    withExtension(NATIVE_STRUCTURED_OUTPUT_EXTENSION, mode)

/**
 * Read the native structured-output mode from [LlmOptions].
 */
@OptIn(InternalExtensionApi::class)
@JvmName("getNativeStructuredOutput")
fun LlmOptions.getNativeStructuredOutput(): NativeStructuredOutputMode? =
    getExtension(NATIVE_STRUCTURED_OUTPUT_EXTENSION)
