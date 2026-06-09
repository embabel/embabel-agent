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
 *
 * DEFAULT means "let Embabel decide from capability, schema compatibility, and API policy."
 */
enum class NativeStructuredOutputMode {
    DEFAULT,
    ENABLED,
    DISABLED,
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
