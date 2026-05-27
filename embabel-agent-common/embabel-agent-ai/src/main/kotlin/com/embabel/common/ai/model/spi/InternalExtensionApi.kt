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
package com.embabel.common.ai.model.spi

/**
 * Marks APIs that are internal extension mechanisms for LlmOptions.
 *
 * These APIs are intended for use by provider-specific modules
 * (e.g., embabel-agent-anthropic-autoconfigure) to add provider-specific
 * configuration without coupling the core LlmOptions to specific providers.
 *
 * Application code should use provider-specific extension functions instead
 * (e.g., withAnthropicCaching() from the Anthropic module).
 *
 * Use with caution as these APIs may change without notice.
 */
@RequiresOptIn(
    message = "This is an internal LlmOptions extension API intended for provider modules. " +
        "Application code should use provider-specific extension functions instead. " +
        "Use with caution as it may change without notice.",
    level = RequiresOptIn.Level.ERROR
)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class InternalExtensionApi
