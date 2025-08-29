/*
 * Copyright 2024-2025 Embabel Software, Inc.
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

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Properties for the ChatClientLlmOperations operations
 * @param maybePromptTemplate template to use for the "maybe" prompt, which
 *  * can enable a failure result if the LLM does not have enough information to
 *  * create the desired output structure.
 */
@ConfigurationProperties(prefix = "embabel.llm-operations.prompts")
data class LlmOperationsPromptsProperties(
    val maybePromptTemplate: String = "maybe_prompt_contribution",
    val generateExamplesByDefault: Boolean = true,
    val defaultTimeout: Duration = Duration.ofSeconds(60),
)
