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
package com.embabel.agent.api.common.workflow.loop

import com.embabel.common.core.types.ZeroToOne
import com.fasterxml.jackson.annotation.JsonPropertyDescription

/**
 * Feedback on a generated output
 */
interface Feedback {
    @get:JsonPropertyDescription("Feedback score between 0.0 and 1.0 where 0.0 is worst and 1.0 is best")
    val score: ZeroToOne
}

/**
 * Convenient implementation of [Feedback] that contains textual feedback.
 */
data class TextFeedback(
    override val score: ZeroToOne,
    val feedback: String,
) : Feedback
