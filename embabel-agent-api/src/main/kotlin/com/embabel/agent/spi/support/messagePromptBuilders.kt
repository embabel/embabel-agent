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
package com.embabel.agent.spi.support

import com.embabel.chat.Message
import com.embabel.chat.SystemMessage
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.core.types.Named
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Separator used between prompt elements when building consolidated prompts.
 */
internal const val PROMPT_ELEMENT_SEPARATOR = "\n----\n"

private val logger = LoggerFactory.getLogger("com.embabel.agent.spi.support.PromptContributions")

/**
 * A contribution taking longer than this is doing I/O — a network call, a database query, an
 * embedding — rather than formatting a string, and it sits directly on the critical path of
 * every LLM call that includes it.
 */
private const val SLOW_CONTRIBUTOR_MILLIS = 250L

/**
 * Contributors already reported, so a permanently slow one warns once rather than on every
 * call. A warning repeated every turn is a warning people learn to scroll past, and the
 * information does not change between turns; the per-call figures remain at debug level.
 *
 * Bounded, because the identity comes from the contributor itself: a [Named] contributor whose
 * name varies per instance - a reference named after the user or the query, exactly the kind
 * that does I/O and so is exactly the kind that lands here - would otherwise grow this set for
 * the life of the process.
 *
 * Deliberately NOT evicting to make room. This is a ledger of "have I already told you about
 * this one", not a leaderboard: dropping an entry would let a permanently slow contributor warn
 * again later, which is the churn warn-once exists to prevent. Reaching the cap is announced
 * once (see [firstSighting]) so the resulting blindness is visible rather than silent, and every
 * slow contributor still reports at debug regardless.
 */
internal const val MAX_WARNED_SLOW_CONTRIBUTORS = 200

internal val warnedSlowContributors = ConcurrentHashMap.newKeySet<String>()

private val slowContributorCapReported = AtomicBoolean(false)

/**
 * Clears warn-once state. For tests: this is process-global by design, so a test that does not
 * reset it depends on execution order.
 */
internal fun resetSlowContributorReporting() {
    warnedSlowContributors.clear()
    slowContributorCapReported.set(false)
}

/**
 * Whether [id] is the first sighting of a slow contributor, and so worth a warning.
 *
 * Racing to the cap can admit a few entries beyond it. That costs a handful of strings, where
 * making it exact would cost a lock on the prompt assembly path.
 */
private fun firstSighting(id: String): Boolean {
    if (warnedSlowContributors.size >= MAX_WARNED_SLOW_CONTRIBUTORS) {
        // Say so, once. Otherwise a deployment that crosses the cap goes quietly blind to every
        // new slow contributor after it, and the absence of warnings reads as "nothing is slow"
        // rather than "reporting stopped".
        if (slowContributorCapReported.compareAndSet(false, true)) {
            logger.warn(
                """
                Slow-contributor reporting has reached its cap of {} distinct contributors and will
                not warn about new ones. Per-call figures remain at debug. Crossing this cap usually
                means contributor names vary per instance rather than that you have {} slow ones.
                """.trimIndent(),
                MAX_WARNED_SLOW_CONTRIBUTORS, MAX_WARNED_SLOW_CONTRIBUTORS,
            )
        }
        return false
    }
    return warnedSlowContributors.add(id)
}

/**
 * Builds a prompt contributions string from prompt contributors.
 *
 * Contributors whose [PromptContributor.contribution] is blank are dropped before
 * joining, so a conditional contributor (e.g. one that returns "" when it has
 * nothing relevant to say this turn) does not leave a dangling
 * `$PROMPT_ELEMENT_SEPARATOR` artifact between two real elements.
 *
 * @param interactionContributors Contributors from the LlmInteraction
 * @param llmContributors Contributors from the LlmService
 * @return Consolidated prompt contributions string
 */
internal fun buildPromptContributionsString(
    interactionContributors: List<PromptContributor>,
    llmContributors: List<PromptContributor>,
): String = (interactionContributors + llmContributors)
    .map { it.timedContribution() }
    .filter { it.isNotBlank() }
    .joinToString(PROMPT_ELEMENT_SEPARATOR)

/**
 * Invoke a contributor, reporting it if it is slow enough to matter.
 *
 * Contributions are gathered while assembling the prompt, so their cost lands INSIDE the
 * LLM call and is invisible from outside it — an application can add hundreds of
 * milliseconds to every turn through one `contribution()` implementation and see only what
 * looks like a slow provider. Assembly itself is well under a millisecond
 * (`PromptAssemblyPerformanceTest`), so when this path is slow, a contributor is why.
 */
private fun PromptContributor.timedContribution(): String {
    val startedNanos = System.nanoTime()
    val contribution = contribution()
    val elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000
    if (elapsedMillis >= SLOW_CONTRIBUTOR_MILLIS) {
        val id = describeForLog()
        if (firstSighting(id)) {
            logger.warn(
                """
                Prompt contributor '{}' took {}ms. It runs on every LLM call that includes it,
                before the model is invoked, so this is added latency on every turn.
                Further occurrences are logged at debug.
                """.trimIndent(),
                id, elapsedMillis,
            )
        } else {
            logger.debug("Prompt contributor '{}' took {}ms", id, elapsedMillis)
        }
    } else if (logger.isDebugEnabled) {
        logger.debug("Prompt contributor '{}' took {}ms", describeForLog(), elapsedMillis)
    }
    return contribution
}

/**
 * Contributors have no name of their own, but many are also [Named] (references, skills),
 * and a name is far more actionable in a warning than a decorator's class.
 */
private fun PromptContributor.describeForLog(): String =
    (this as? Named)?.name?.takeIf { it.isNotBlank() }
        ?: this::class.simpleName
        ?: this::class.java.name

/**
 * Partitions messages into system message content and non-system messages.
 * This enables consolidating all system content at the beginning of the prompt.
 *
 * @param messages The messages to partition
 * @return Pair of (system content strings, non-system messages)
 */
internal fun partitionMessages(messages: List<Message>): Pair<List<String>, List<Message>> {
    val systemContent = mutableListOf<String>()
    val nonSystemMessages = mutableListOf<Message>()
    for (message in messages) {
        if (message is SystemMessage) {
            systemContent.add(message.content)
        } else {
            nonSystemMessages.add(message)
        }
    }
    return systemContent to nonSystemMessages
}

/**
 * Consolidates multiple system content strings into a single string.
 * Follows OpenAI best practices and ensures compatibility with models like DeepSeek
 * that have strict message ordering requirements.
 *
 * @param contents The content strings to consolidate
 * @return Single consolidated string with contents joined by double newlines
 */
internal fun buildConsolidatedSystemMessage(vararg contents: String): String =
    contents.filter { it.isNotEmpty() }.joinToString("\n\n")

/**
 * Builds a message list with all system content consolidated into a single
 * system message at the beginning.
 *
 * Partitions input messages, extracts system content, merges with prompt contributions,
 * and returns a new list with consolidated system message followed by non-system messages.
 *
 * @param messages The input messages (may contain system messages to extract)
 * @param promptContributions The prompt contributions string to include
 * @return Message list with consolidated system message first
 */
internal fun buildConsolidatedPromptMessages(
    messages: List<Message>,
    promptContributions: String,
): List<Message> {
    val (systemContent, nonSystemMessages) = partitionMessages(messages)
    val allSystemContent = buildConsolidatedSystemMessage(promptContributions, *systemContent.toTypedArray())
    return buildList {
        if (allSystemContent.isNotEmpty()) {
            add(SystemMessage(allSystemContent))
        }
        addAll(nonSystemMessages)
    }
}
