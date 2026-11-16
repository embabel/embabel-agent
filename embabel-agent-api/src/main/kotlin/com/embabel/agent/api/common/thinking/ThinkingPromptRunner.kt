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
package com.embabel.agent.api.common.thinking

import com.embabel.agent.api.common.PromptRunner

/**
 * A [PromptRunner] that can run prompts with thinking extraction.
 *
 * A runner offers thinking by implementing this interface. The compiler then requires it to
 * implement [thinking]. [PromptRunner]'s own default [thinking] throws
 * [UnsupportedOperationException].
 *
 * The type is the one check: `is ThinkingPromptRunner`. Extraction is prompt based, so it works
 * whatever the selected model is. When the model returned no reasoning,
 * [com.embabel.common.core.thinking.ThinkingResponse.thinkingBlocks] is empty. The runner returned
 * by `Ai` on the platform is always a ThinkingPromptRunner. In unit tests, `FakeOperationContext`
 * returns a `FakePromptRunner`, which is not one. Check the type when the runner may come from
 * somewhere else.
 *
 * Usage (Kotlin):
 * ```kotlin
 * val runner = context.ai().withDefaultLlm()
 * if (runner is ThinkingPromptRunner) {
 *     val response = runner.thinking().createObject("What is 2 + 2?", Answer::class.java)
 *     val answer = response.result
 *     val reasoning = response.thinkingBlocks
 * }
 * ```
 *
 * Usage (Java):
 * ```java
 * PromptRunner runner = context.ai().withDefaultLlm();
 * if (runner instanceof ThinkingPromptRunner thinkingRunner) {
 *     ThinkingResponse<Answer> response = thinkingRunner.thinking().createObject("What is 2 + 2?", Answer.class);
 * }
 * ```
 */
interface ThinkingPromptRunner : PromptRunner {

    /**
     * True unless an implementation reports what its provider says. Overrides the [PromptRunner]
     * default of false.
     */
    @Deprecated(
        message = "Not a gate: thinking() does not consult it. Check `runner is ThinkingPromptRunner` before calling thinking().",
    )
    override fun supportsThinking(): Boolean = true

    /**
     * Return the [PromptRunner.Thinking] operations of this runner.
     *
     * @return thinking operations returning results with extracted reasoning
     */
    override fun thinking(): PromptRunner.Thinking
}
