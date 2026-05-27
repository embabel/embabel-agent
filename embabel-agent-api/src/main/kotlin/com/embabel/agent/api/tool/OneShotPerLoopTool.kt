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
package com.embabel.agent.api.tool

/**
 * Decorator that allows the underlying [delegate] to run **at most once
 * per agentic loop** (as identified by [ToolCallContext.LOOP_ID_KEY]).
 * Subsequent calls within the same loop short-circuit with an
 * "ALREADY LOADED" message that includes the caller-supplied [advice]
 * — instead of re-running the tool.
 *
 * Use when:
 *
 * - A tool returns content that, once delivered, lives in the LLM's
 *   conversation history for the rest of the turn — calling it again
 *   wastes tokens and round-trips and produces no new information.
 *   Skill-activator tools and reference-loaders are the canonical case.
 * - A weak chat model (qwen, gpt-oss, smaller open models) reflexively
 *   re-calls the same tool turn after turn even when system-prompt
 *   discipline says not to. Words alone don't hold; this wrapper makes
 *   the constraint mechanical.
 *
 * The [advice] string lets callers tailor the second-call message to
 * whatever the LLM should do next ("write your `js` block now", "answer
 * the user", "stop and report"). Required because the default would be
 * scenario-specific guesswork — better to make the caller think about it.
 *
 * Per-loop isolation is provided by [LoopMemo] reading the loop id
 * from [ToolCallContext]. The orchestrator is responsible for stamping
 * a fresh loop id per turn (typically via
 * `PromptRunner.withToolCallContext(...)`); without one, every call
 * registers as "first time" and the wrapper degrades to a passthrough.
 *
 * Example:
 *
 *     val gated = OneShotPerLoopTool(
 *         delegate = skillActivator,
 *         advice = "Write your script now using the skill body above.",
 *     )
 *
 * Implements [DelegatingTool] so framework strategies that unwrap
 * decorators can find the underlying tool, and so both `call` overloads
 * route through the canonical two-arg method.
 */
class OneShotPerLoopTool(
    override val delegate: Tool,
    private val advice: String,
) : DelegatingTool {

    private val memo = LoopMemo()

    override val definition: Tool.Definition = delegate.definition

    override fun call(input: String, context: ToolCallContext): Tool.Result {
        return if (memo.firstTimeIn(context)) {
            delegate.call(input, context)
        } else {
            Tool.Result.text(
                """
                    ALREADY LOADED. The body of '${delegate.definition.name}' was returned earlier in this turn — read it from your conversation history above. Do not call this tool again. $advice
                """.trimIndent(),
            )
        }
    }
}
