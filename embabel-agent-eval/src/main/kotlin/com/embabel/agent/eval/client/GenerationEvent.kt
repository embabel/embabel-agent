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
package com.embabel.agent.eval.client

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonTypeInfo(
    use = JsonTypeInfo.Id.DEDUCTION,
)
@JsonSubTypes(
    JsonSubTypes.Type(value = SystemPromptEvent::class),
    JsonSubTypes.Type(value = FunctionCallEvent::class),
    JsonSubTypes.Type(value = FunctionResponseEvent::class),
    JsonSubTypes.Type(value = BoogieRequestEvent::class),
)
sealed interface GenerationEvent

data class SystemPromptEvent(
    val systemPrompt: String
) : GenerationEvent

data class BoogieRequestEvent(
    val content: String,
    val summaryDirection: String?,
) : GenerationEvent

data class FunctionCallEvent(
    val request: FunctionCallRequest,
) : GenerationEvent

data class FunctionCallRequest(
    val function: FunctionIdentification,
    val arguments: Map<String, Any>,
    val context: String,
)

data class FunctionResponseEvent(
    val output: String,
) : GenerationEvent

data class FunctionIdentification(
    val name: String,
)
