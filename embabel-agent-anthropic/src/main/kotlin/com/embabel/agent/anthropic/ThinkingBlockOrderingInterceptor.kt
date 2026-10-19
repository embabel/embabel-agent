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

import com.anthropic.core.http.HttpRequest
import com.anthropic.core.http.HttpRequestBody
import com.anthropic.core.http.Interceptor
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Anthropic SDK interceptor that fixes content block ordering in assistant messages
 * before they reach the Anthropic API.
 *
 * THE BUG (Spring AI issue / embabel issue #2121):
 * When Claude thinks and writes text before calling a tool, the Anthropic API returns:
 *   [thinking_block, tool_use_block]
 * Spring AI (AnthropicChatModel.createRequest) rebuilds the conversation history for the
 * next tool-loop iteration as:
 *   [text_block, thinking_block, tool_use_block]           <- WRONG
 * Anthropic requires thinking blocks to appear BEFORE text blocks:
 *   [thinking_block, text_block, tool_use_block]           <- CORRECT
 *
 * Accounts created before 2026-08-31 silently accepted wrong ordering.
 * Accounts created on or after 2026-08-31 get HTTP 400
 * (thinking.block_binding.prefix_mismatch_behavior defaults to "error").
 *
 * THE DUPLICATE TEXT PROBLEM:
 * Spring AI also creates a duplicate text_block whose content is identical to the
 * thinking block's text. The original Anthropic response contained no such text_block —
 * Spring AI adds it by mapping thinking text into AssistantMessage.text. This interceptor
 * removes those duplicates.
 *
 * HOW THE FIX WORKS:
 * The interceptor sits at the raw HTTP layer (before bytes leave the JVM), using the
 * Anthropic Java SDK's Interceptor hook (registered via addInterceptor on the client builder).
 * For every outgoing POST it:
 *   1. Exits immediately for non-JSON requests (streaming, GET) — no overhead
 *   2. Parses the JSON body into a mutable Jackson tree
 *   3. Exits immediately if the request has no thinking config (non-thinking calls skipped)
 *   4. For each assistant message: removes duplicate text blocks, then sorts to
 *      [thinking, text, tool_use]
 *   5. If nothing changed, forwards the original (buffered) request unchanged
 *
 * Wired via [AnthropicModelFactory.createAnthropicClient].
 */
internal object ThinkingBlockOrderingInterceptor {

    private val mapper = ObjectMapper()

    // Anthropic's required position for each content block type.
    // Any unknown type (e.g. "image") gets Int.MAX_VALUE and sorts to the end.
    private val BLOCK_ORDER = mapOf(
        "thinking"          to 0,   // internal reasoning — must appear first
        "redacted_thinking" to 1,   // opaque thinking block (Anthropic may return this for cached thinking)
        "text"              to 2,   // visible text response
        "tool_use"          to 3,   // tool call — must appear last
    )

    /**
     * Returns an [Interceptor] for use with
     * [com.anthropic.client.okhttp.AnthropicOkHttpClient.Builder.addInterceptor].
     *
     * [Interceptor.syncOnly] is a factory on the Anthropic SDK that creates an interceptor
     * from a synchronous lambda. The lambda receives:
     *   - httpClient: the next handler in the chain (ultimately calls the real Anthropic API)
     *   - request:    the outgoing HTTP request (URL, headers, body)
     *   - options:    per-request options (timeouts, retries, etc.)
     * The lambda must return an HttpResponse — produced here by forwarding the
     * (possibly modified) request via httpClient.execute().
     *
     * [return@syncOnly] is Kotlin's labeled return: it exits the lambda (the syncOnly { ... }
     * block) and produces a value for it — equivalent to a plain return in a Java anonymous class.
     */
    fun asInterceptor(): Interceptor = Interceptor.syncOnly { httpClient, request, options ->

        // All Anthropic API calls with a body are JSON POST requests.
        // No body means a GET or streaming request — nothing to fix, pass through immediately.
        // The null check is the guard; buffered() in buildFixedRequest reads body separately.
        if (request.body == null) return@syncOnly httpClient.execute(request, options)

        // Wrap ALL our fix logic in a single try/catch.
        // If anything we do throws — buffering failure, JSON parse error, re-serialization
        // error, anything — fall back to forwarding the original request unchanged.
        // httpClient.execute() is intentionally OUTSIDE this block: network errors and
        // Anthropic API errors must propagate to Spring AI's retry and error handling, not
        // be swallowed here.
        val requestToSend: HttpRequest = try {
            buildFixedRequest(request)
        } catch (_: Exception) {
            // Our fix logic failed — forward the original request rather than dropping it
            request
        }

        httpClient.execute(requestToSend, options)
    }

    /**
     * Returns the [request] with its content block ordering corrected, or the original
     * (buffered) request if no correction was needed.
     *
     * Called from within the try/catch in [asInterceptor] — any exception propagates up
     * and causes fallback to the original request.
     */
    private fun buildFixedRequest(request: HttpRequest): HttpRequest {
        // Buffer the request before reading its body.
        // body.json() reads from the underlying stream; without buffering, a non-repeatable
        // body would be consumed here and the forwarded request would have an empty body.
        // buffered() wraps the body bytes in memory so the body can be read multiple times.
        val buffered = request.buffered()

        // Parse the JSON body into a mutable Jackson tree so we can edit it in place.
        // Returns null if the body is empty — forward buffered as-is in that case.
        // body is a Kotlin property on HttpRequest — access without parentheses
        val root: ObjectNode = buffered.body?.json(ObjectNode::class.java)
            ?: return buffered

        // Fast exit for non-thinking requests.
        // output_config (adaptive thinking via effort=xhigh) and thinking (budget-based thinking)
        // are only present when thinking is enabled. Without them Anthropic never generates
        // thinking blocks, so there is nothing to reorder. Skipping the message scan here
        // avoids any overhead on plain (non-thinking) chat requests.
        if (!root.has("output_config") && !root.has("thinking")) {
            return buffered
        }

        // messages is the top-level conversation history array — null means malformed body
        val messages = root.get("messages") as? ArrayNode ?: return buffered

        // Fix ordering in-place; changed=true means at least one message was modified
        val changed = fixMessages(messages)

        // If the content was corrected, create a new request carrying the fixed JSON body.
        // Otherwise forward the buffered original — safe because buffered() above ensured
        // the body is repeatable and can still be read by the SDK.
        return if (changed) {
            buffered.toBuilder().body(HttpRequestBody.ofJson(root)).build()
        } else {
            buffered
        }
    }

    /**
     * Scans [messages] for assistant messages with out-of-order content blocks and fixes
     * them in place on the Jackson tree. Returns true if any message was modified.
     *
     * Exposed as `internal` so unit tests can drive it directly with a Jackson tree,
     * without needing a real [com.anthropic.core.http.HttpRequest].
     */
    internal fun fixMessages(messages: ArrayNode): Boolean {
        var changed = false

        messages.forEach { message ->
            // Only assistant messages have content blocks to reorder.
            // User and tool_result messages are not affected.
            if (message.get("role")?.asText() != "assistant") return@forEach

            // Some assistant messages have plain string content (no tool calls, no thinking).
            // Those arrive as a JSON string, not an array — skip them.
            val content = message.get("content") as? ArrayNode ?: return@forEach

            val blocks = content.toList()

            // Collect the text of every thinking block in this message.
            // Spring AI copies this same text into a separate text_block — we remove the duplicate.
            val thinkingTexts: Set<String> = blocks
                .filter { it.get("type")?.asText() == "thinking" }
                .mapNotNull { it.get("thinking")?.asText() }
                .toSet()

            val sorted = blocks
                // Remove text_blocks whose content is identical to a thinking block.
                // The original Anthropic response had no such text_block; Spring AI introduced it
                // by copying thinking text into AssistantMessage.text.
                .filter { block ->
                    if (block.get("type")?.asText() != "text") return@filter true  // keep non-text blocks unchanged
                    val text = block.get("text")?.asText() ?: return@filter true   // keep if text field absent
                    text !in thinkingTexts                                          // drop if it duplicates thinking content
                }
                // Sort remaining blocks to Anthropic's required order: thinking → text → tool_use
                .sortedBy { block -> BLOCK_ORDER[block.get("type")?.asText()] ?: Int.MAX_VALUE }

            // Reference equality (===): same JsonNode object at the same position means no change.
            // The size check catches removed duplicate text blocks.
            val reordered = sorted.size != blocks.size ||
                    sorted.zip(blocks).any { (after, before) -> after !== before }

            if (reordered) {
                // Rebuild the content array in-place: clear all existing nodes, then
                // re-add in the corrected order so the parent ObjectNode sees the change
                content.removeAll()
                sorted.forEach { content.add(it) }
                changed = true  // signal to caller that at least one message was modified
            }
        }

        return changed
    }
}
