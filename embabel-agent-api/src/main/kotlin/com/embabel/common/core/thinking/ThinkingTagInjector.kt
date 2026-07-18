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
package com.embabel.common.core.thinking

import com.embabel.common.ai.prompt.PromptContribution
import com.embabel.common.ai.prompt.PromptContributionLocation
import com.embabel.common.ai.prompt.PromptContributor

/**
 * A [PromptContributor] that injects a system-level instruction telling the LLM
 * to generate reasoning inside the specified XML-style tag.
 *
 * This is used when [PromptRunner.thinking] is called with a custom tag name,
 * to ensure the LLM actually produces thinking blocks even when the user's
 * prompt does not explicitly ask for reasoning.
 *
 * Example: with tag "decision_reasoning", it injects:
 * ```
 * You MUST generate reasoning inside <decision_reasoning>...</decision_reasoning> tags
 * before providing your final answer.
 * ```
 *
 * @param tag The XML tag name the LLM should use for its reasoning (e.g. "think", "analysis", "decision_reasoning")
 */
data class ThinkingTagInjector(
    val tag: String,
) : PromptContributor {

    override val promptContributionLocation: PromptContributionLocation
        get() = PromptContributionLocation.END

    override fun contribution(): String {
        return buildString {
            append("You MUST generate reasoning inside <$tag>...</$tag> tags ")
            append("before providing your final answer. ")
            append("Put your step-by-step analysis within these tags.")
        }
    }

    override fun promptContribution(): PromptContribution {
        return PromptContribution(
            content = contribution(),
            location = promptContributionLocation,
            role = "thinking_tag_instruction",
        )
    }
}
