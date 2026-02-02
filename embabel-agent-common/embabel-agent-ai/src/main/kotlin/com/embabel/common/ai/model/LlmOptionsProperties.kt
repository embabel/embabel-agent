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

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Shared configuration options for all AI model providers.
 * These properties apply across OpenAI, Anthropic, Bedrock, etc.
 */
@ConfigurationProperties(prefix = "embabel.agent.platform.models.options")
class LlmOptionsProperties {
    /**
     * HTTP headers to include in all model API requests.
     * Common headers like rate limiting, redaction policies, etc.
     */
    var httpHeaders: Map<String, String> = emptyMap()

}
