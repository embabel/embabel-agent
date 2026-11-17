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
package com.embabel.agent.openai.decision

import com.embabel.common.ai.decision.DecisionCall
import com.embabel.common.ai.decision.DecisionCallDsl
import com.embabel.common.ai.decision.DecisionOption
import org.jetbrains.annotations.ApiStatus

/**
 * The per-call options the OpenAI Decisions API accepts, as typed keys you can set on a
 * [DecisionCall].
 *
 * From Java, set them with `withOption`:
 *
 * ```java
 * DecisionCall call = DecisionCall.of(request)
 *     .withOption(OpenAiDecisionOptions.SAFETY_IDENTIFIER, "user-42");
 * ```
 *
 * From Kotlin, the [openAi] block sets them by name.
 */
@ApiStatus.Experimental
object OpenAiDecisionOptions {

    /**
     * A key that groups requests sharing a long common prefix, so OpenAI can reuse its prompt
     * cache across them. OpenAI accepts at most 64 characters.
     */
    @JvmField
    val PROMPT_CACHE_KEY: DecisionOption<String> = DecisionOption.of("openai", "prompt-cache-key", String::class.java)

    /**
     * A stable identifier for the end user, which OpenAI uses to spot misuse by one user without
     * blocking everyone else. Hash anything that could identify the person. OpenAI accepts at
     * most 128 characters.
     */
    @JvmField
    val SAFETY_IDENTIFIER: DecisionOption<String> = DecisionOption.of("openai", "safety-identifier", String::class.java)
}

/**
 * The receiver of the Kotlin [openAi] block. Each property you give a value becomes an option on
 * the call; a property left null adds nothing.
 */
@ApiStatus.Experimental
@DecisionCallDsl
class OpenAiDecisionScope internal constructor() {

    /** Sets [OpenAiDecisionOptions.PROMPT_CACHE_KEY]. OpenAI accepts at most 64 characters. */
    var promptCacheKey: String? = null

    /** Sets [OpenAiDecisionOptions.SAFETY_IDENTIFIER]. OpenAI accepts at most 128 characters. */
    var safetyIdentifier: String? = null
}

/**
 * Sets the OpenAI options on a call by name:
 *
 * ```kotlin
 * val call = DecisionCall(request) {
 *     openAi {
 *         promptCacheKey = "triage-v1"
 *         safetyIdentifier = "user-42"
 *     }
 * }
 * ```
 *
 * Only properties you give a non-null value are written to the call. This block accepts any
 * string and leaves the length limits in the property docs for the OpenAI adapter to check.
 *
 * @param block assigns the options to set
 */
@ApiStatus.Experimental
@JvmSynthetic
fun DecisionCall.Scope.openAi(block: OpenAiDecisionScope.() -> Unit) {
    val scope = OpenAiDecisionScope().apply(block)
    scope.promptCacheKey?.let { option(OpenAiDecisionOptions.PROMPT_CACHE_KEY, it) }
    scope.safetyIdentifier?.let { option(OpenAiDecisionOptions.SAFETY_IDENTIFIER, it) }
}
