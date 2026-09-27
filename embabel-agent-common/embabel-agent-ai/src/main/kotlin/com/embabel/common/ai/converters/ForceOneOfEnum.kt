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
package com.embabel.common.ai.converters

/**
 * Marks an enum whose constants carry [@JsonPropertyDescription][com.fasterxml.jackson.annotation.JsonPropertyDescription]
 * annotations that should reach the model as per-constant descriptions in the generated JSON Schema.
 *
 * When this annotation is present on an enum class, [JacksonOutputConverter] emits the enum as
 * `{ "type": "string", "oneOf": [ { "const": "…", "description": "…" }, … ] }` instead of the
 * default `{ "type": "string", "enum": [ … ] }`.
 *
 * **Trade-off**: the `oneOf` keyword causes Embabel to skip the native structured-output path
 * (which is gated by [hasUnsupportedJsonSchemaKeywords][com.embabel.common.ai.converters.hasUnsupportedJsonSchemaKeywords])
 * and fall back to the prompt-based path for that call. The descriptions still reach the model
 * through the prompt schema in [JacksonOutputConverter.getFormat]. This applies to all providers,
 * not only OpenAI.
 *
 * Usage:
 * ```kotlin
 * @ForceOneOfEnum
 * enum class Priority {
 *     @JsonPropertyDescription("Needs same-day response") URGENT,
 *     @JsonPropertyDescription("Standard turnaround")    NORMAL,
 *     OTHER,
 * }
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ForceOneOfEnum
