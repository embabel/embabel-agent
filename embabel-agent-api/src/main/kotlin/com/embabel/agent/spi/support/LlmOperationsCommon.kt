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
package com.embabel.agent.spi.support

import com.embabel.common.textio.template.CompiledTemplate
import com.embabel.common.textio.template.TemplateRenderer

/**
 * Common reusable artifacts for LlmOperations implementations.
 * These are framework-agnostic utilities shared by both the base tool loop
 * implementation and Spring AI-specific implementations.
 */

/**
 * Structure to be returned by the LLM for "if possible" operations.
 * Allows the LLM to return a result structure under success, or an error message.
 * One of success or failure must be set, but not both.
 */
internal data class MaybeReturn<T>(
    val success: T? = null,
    val failure: String? = null,
) {
    fun toResult(): Result<T> {
        return if (success != null) {
            Result.success(success)
        } else {
            Result.failure(Exception(failure))
        }
    }
}

/**
 * No-op TemplateRenderer that throws UnsupportedOperationException when used.
 * Used as default when no real TemplateRenderer is provided.
 * Operations requiring template rendering (e.g., doTransformIfPossible) will fail
 * clearly if this default is used.
 */
internal object NoOpTemplateRenderer : TemplateRenderer {
    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("TemplateRenderer not configured. Provide a real TemplateRenderer for operations that require template rendering.")

    override fun load(templateName: String): String = unsupported()
    override fun renderLoadedTemplate(templateName: String, model: Map<String, Any>): String = unsupported()
    override fun renderLiteralTemplate(template: String, model: Map<String, Any>): String = unsupported()
    override fun compileLoadedTemplate(templateName: String): CompiledTemplate = unsupported()
}
