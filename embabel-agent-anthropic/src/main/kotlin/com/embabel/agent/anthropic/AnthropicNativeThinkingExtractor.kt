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

import com.embabel.agent.spi.support.springai.SpringAiNativeThinkingExtractor
import com.embabel.common.core.thinking.ThinkingBlock
import com.embabel.common.core.thinking.ThinkingOrigin
import com.embabel.common.core.thinking.ThinkingTagType
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ChatResponse

/**
 * Extracts native thinking blocks from an Anthropic Claude adaptive thinking response.
 *
 * ## Spring AI 2.0 representation (current)
 *
 * In Spring AI 2.0, the Anthropic integration was rewritten to use the official
 * Anthropic Java SDK (`com.anthropic:anthropic-java`). Thinking blocks from the API
 * are now consolidated onto a single `AnthropicAssistantMessage` (a non-public
 * subclass of `AssistantMessage`) per LLM response turn, rather than being split into
 * separate `Generation` objects.
 *
 * Thinking blocks are stored as `List<AnthropicChatModel.AnthropicThinkingContent>`
 * under the key `"anthropicThinkingContents"` in `AssistantMessage.getMetadata()`.
 * Because `AnthropicThinkingContent` is a non-public record inside
 * `AnthropicChatModel`, this extractor accesses it via reflection using the public
 * record-component accessors `thinking()` and `signature()`.
 *
 * ### Anthropic API response payload (simplified)
 *
 * ```json
 * {
 *   "content": [
 *     {
 *       "type": "thinking",
 *       "thinking": "Let me analyze which month has the highest average...",
 *       "signature": "EqoBCkgIARgCIkDo8fk..."
 *     },
 *     {
 *       "type": "text",
 *       "text": "{\"name\": \"July\", \"temperature\": 98}"
 *     }
 *   ]
 * }
 * ```
 *
 * Spring AI 2.0 maps this so that `response.results[0].output.metadata` contains:
 * ```
 * "anthropicThinkingContents" -> [AnthropicThinkingContent(thinking="Let me analyze...", signature="EqoBCkgI...")]
 * ```
 *
 * ## Spring AI ≤1.x representation (no longer in use)
 *
 * In ≤1.x, thinking came as a separate generation alongside the answer:
 * ```
 * results[0]: text="Let me analyze..."  metadata={"signature" -> "EqoBCkgI..."}  // thinking
 * results[1]: text="<json answer>"      metadata={}                               // answer
 * ```
 * Thinking text was read from the generation whose metadata contained `"signature"`.
 *
 * In Spring AI 2.0, thinking is consolidated onto the answer generation (see example above):
 * ```
 * results[0]: text="<json answer>"  metadata={"anthropicThinkingContents" -> [AnthropicThinkingContent(...)]}
 * ```
 * The `"signature"` key may still appear in metadata but the `.text` field is empty —
 * thinking text moved out of `.text`, now read via [extractNativeThinkingContents] with
 * `anthropicThinkingContents` metadata as fallback.
 * Filtering by `"signature"` to read `.text` therefore always returns empty.
 *
 * ## Signature preservation
 *
 * The `signature` value from each thinking block is preserved in [ThinkingBlock.metadata]
 * under the key `"signature"`. It must be echoed back in the `thinking` content block
 * when continuing a multi-turn conversation, as required by the Anthropic API.
 */
object AnthropicNativeThinkingExtractor : SpringAiNativeThinkingExtractor {

    /**
     * Key under which Spring AI 2.0's `AnthropicChatModel` stores the list of
     * `AnthropicThinkingContent` objects in `AssistantMessage.getMetadata()`.
     *
     * Mirrors the non-public constant `AnthropicChatModel.ANTHROPIC_THINKING_CONTENTS_PROPERTY`.
     */
    private const val THINKING_CONTENTS_KEY = "anthropicThinkingContents"

    private val logger = LoggerFactory.getLogger(AnthropicNativeThinkingExtractor::class.java)

    override fun extract(response: ChatResponse): List<ThinkingBlock> {
        logger.debug("extract called: {} generations", response.results.size)
        return response.results.flatMap { generation ->
            val output = generation.output
            logger.debug("generation output type={} metadata keys={}", output.javaClass.simpleName, output.metadata?.keys)
            @Suppress("UNCHECKED_CAST")
            val items: List<*>? = extractNativeThinkingContents(output)                          // primary: reflection on getThinkingContents()
                ?: output.metadata?.get(THINKING_CONTENTS_KEY) as? List<*>             // fallback: metadata key
            if (items.isNullOrEmpty()) return@flatMap emptyList<ThinkingBlock>()
            items.mapNotNull { extractBlock(it) }
        }
    }

    /**
     * Reads thinking contents directly from `AnthropicAssistantMessage.getThinkingContents()`.
     *
     * Spring AI stores thinking blocks on the object field rather than only in the metadata map.
     * This method mirrors how Spring AI itself reads thinking blocks when building the next
     * request's conversation history. Falls back to null if the method is absent (non-Anthropic
     * message type), allowing the caller to try the metadata key instead.
     */
    private fun extractNativeThinkingContents(output: Any): List<*>? = try {
        val method = output.javaClass.getDeclaredMethod("getThinkingContents")
            .apply { isAccessible = true }
        (method.invoke(output) as? List<*>)?.takeIf { it.isNotEmpty() }
    } catch (_: NoSuchMethodException) {
        null
    }

    /**
     * Converts one `AnthropicChatModel.AnthropicThinkingContent` instance to a [ThinkingBlock].
     *
     * `AnthropicThinkingContent` is a non-public Java record inside `AnthropicChatModel`.
     * Its public record-component accessors `thinking()` and `signature()` are invoked via
     * reflection so that this class does not need to be on the exported API surface.
     *
     * Returns `null` when [item] is null, the `thinking()` text is blank (Anthropic can
     * return an empty thinking block), or reflection fails.
     */
    private fun extractBlock(item: Any?): ThinkingBlock? {
        if (item == null) return null
        return try {
            val thinking = item.javaClass.getDeclaredMethod("thinking")
                .apply { isAccessible = true }
                .invoke(item) as? String ?: return null
            if (thinking.isBlank()) return null
            val sig = item.javaClass.getDeclaredMethod("signature")
                .apply { isAccessible = true }
                .invoke(item) as? String
            ThinkingBlock(
                content = thinking,
                tagType = ThinkingTagType.NO_PREFIX,
                tagValue = "",
                origin = ThinkingOrigin.NATIVE,
                metadata = if (sig != null) mapOf("signature" to sig) else emptyMap(),
            )
        } catch (e: Exception) {
            logger.debug("extractBlock failed for {}: {}", item.javaClass.simpleName, e.message)
            null
        }
    }
}
