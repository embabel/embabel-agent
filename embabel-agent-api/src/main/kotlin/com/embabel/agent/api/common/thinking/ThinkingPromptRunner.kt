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
 * implement [thinking]. A runner that does not implement this interface answers false from
 * [supportsThinking], and its [thinking] throws [UnsupportedOperationException].
 *
 * [supportsThinking] defaults to true here. An implementation whose model is chosen per call
 * overrides it to report what the selected model supports. The runner the platform returns from
 * `Ai` does this.
 *
 * Usage (Kotlin):
 * ```kotlin
 * val runner = context.ai().withDefaultLlm()
 * if (runner is ThinkingPromptRunner && runner.supportsThinking()) {
 *     val response = runner.thinking().createObject("What is 2 + 2?", Answer::class.java)
 *     val answer = response.result
 *     val reasoning = response.thinkingBlocks
 * }
 * ```
 *
 * Usage (Java):
 * ```java
 * PromptRunner runner = context.ai().withDefaultLlm();
 * if (runner instanceof ThinkingPromptRunner thinkingRunner && thinkingRunner.supportsThinking()) {
 *     ThinkingResponse<Answer> response = thinkingRunner.thinking().createObject("What is 2 + 2?", Answer.class);
 * }
 * ```
 *
 * @see com.embabel.agent.api.common.streaming.StreamingPromptRunner the same pattern for streaming
 */
interface ThinkingPromptRunner : PromptRunner {

    /**
     * A ThinkingPromptRunner supports thinking unless it overrides this.
     * Overrides the [PromptRunner] default of false.
     */
    override fun supportsThinking(): Boolean = true

    /**
     * Return the [PromptRunner.Thinking] operations of this runner.
     *
     * @return thinking operations returning results with extracted reasoning
     */
    override fun thinking(): PromptRunner.Thinking
}
