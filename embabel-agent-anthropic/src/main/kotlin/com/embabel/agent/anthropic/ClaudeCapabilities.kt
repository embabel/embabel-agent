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
package com.embabel.agent.anthropic

import com.anthropic.models.messages.OutputConfig

/**
 * Outcome of [ClaudeCapabilities.resolveThinking]: what the converter should send
 * for the thinking field given this model and the requested budget.
 */
sealed class ThinkingMode {
    /** Claude 5.1+ without a thinking budget: omit the thinking field entirely. */
    data object Omit : ThinkingMode()

    /** Old models without a thinking budget: send thinking.type="disabled". */
    data object Disabled : ThinkingMode()

    /**
     * Claude ≥4.7: adaptive thinking. The model decides when and how much to think.
     * [effort] maps the user's budget hint to LOW/MEDIUM/HIGH/XHIGH/MAX effort.
     *
     * [ThinkingConfigAdaptive.Display] controls how thinking content is surfaced in the response.
     * It is always set to [ThinkingConfigAdaptive.Display.SUMMARIZED] — the only option that
     * returns visible reasoning. The default on 5.x is `OMITTED` (empty thinking fields), so
     * SUMMARIZED must be set explicitly. The returned text is a summarized representation of the
     * actual chain of thought; the full reasoning is encrypted in the "signature" field.
     *
     * Billing comes from usage.output_tokens_details.thinking_tokens, not the summary length.
     */
    data class Adaptive(val effort: OutputConfig.Effort) : ThinkingMode()

    /**
     * User provided an explicit token budget → send "thinking": {"type": "enabled", "budget_tokens": N}.
     * Works on all Claude versions including 5.x.
     */
    data class BudgetBased(val budgetTokens: Long) : ThinkingMode()
}

/**
 * Derives API capability flags from a Claude model ID string.
 *
 * Version is encoded as major * 10 + minor, parsed from the last two 1–2-digit numeric
 * segments of the model ID. Date-snapshot suffixes are ignored:
 *   "claude-sonnet-4-5"          → 45
 *   "claude-sonnet-4-5-20250929" → 45  (date suffix stripped)
 *   "claude-fable-5-1"           → 51
 * An unrecognised model ID yields version 0 — treated as an old model that accepts
 * the disabled flag and temperature/topP/topK sampling parameters.
 */
data class ClaudeCapabilities(val version: Int) {

    /** Claude ≥4.7 uses the adaptive thinking API — reserved for Thinking.adaptive() (not yet exposed). */
    val usesAdaptiveThinking: Boolean = version >= 47

    /** Claude 5.1+ rejects thinking.type="disabled" with HTTP 400 — the "thinking" key must be omitted entirely. */
    val rejectsThinkingDisabled: Boolean = version >= 51

    /**
     * Claude <4.7 accepts temperature, topP and topK. Claude ≥4.7 rejects non-default values of
     * all three on every request, whatever the thinking mode.
     */
    val acceptsSampling: Boolean = version < 47

    /**
     * Resolves the thinking configuration to send for a given token budget.
     *
     * @param budget null — thinking not requested (not enabled or no budget set); non-null positive Int — requested token budget.
     * @return the [ThinkingMode] the converter should act on.
     */
    fun resolveThinking(budget: Int?): ThinkingMode = when {
        budget == null && rejectsThinkingDisabled -> ThinkingMode.Omit
        // Claude 5.1+: send no "thinking" key in the request JSON at all.
        // Sending "thinking": {"type": "disabled"} causes HTTP 400 on these models.

        budget == null                            -> ThinkingMode.Disabled
        // Claude ≤5.0: send "thinking": {"type": "disabled"} in the request JSON.
        // These models require the field to confirm thinking is intentionally off.

        usesAdaptiveThinking                      -> ThinkingMode.Adaptive(mapBudgetToEffort(budget))
        // Claude ≥4.7: thinkingEnabled(budget_tokens) is rejected by 5.x with HTTP 400.
        // Map budget to effort; thinkingAdaptive() (no Display) returns full thinking blocks.

        else                                      -> ThinkingMode.BudgetBased(budget.toLong())
        // Claude ≤4.6: send "thinking": {"type": "enabled", "budget_tokens": N}.
    }

    companion object {

        // \d{1,2} caps each component at 2 digits so a date suffix like -20250929 is never mistaken for a version.
        // (?:-\d+)? absorbs the optional date suffix (e.g. claude-sonnet-4-5-20250929 → major=4, minor=5).
        private val VERSION_REGEX = Regex("""(\d{1,2})-(\d{1,2})(?:-\d+)?$""")

        fun of(model: String): ClaudeCapabilities {
            val match = VERSION_REGEX.find(model)
            val version = if (match != null) {
                val major = match.groupValues[1].toInt()
                val minor = match.groupValues[2].toInt()
                major * 10 + minor
            } else {
                0
            }
            return ClaudeCapabilities(version)
        }

        fun mapBudgetToEffort(budget: Int): OutputConfig.Effort = when {
            budget < 1024  -> OutputConfig.Effort.LOW
            budget < 4096  -> OutputConfig.Effort.MEDIUM
            budget < 16384 -> OutputConfig.Effort.HIGH
            budget < 32768 -> OutputConfig.Effort.XHIGH
            else           -> OutputConfig.Effort.MAX
        }
    }
}
