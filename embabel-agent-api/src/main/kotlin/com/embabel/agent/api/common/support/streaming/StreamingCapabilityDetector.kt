/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.api.common.support.streaming

import com.embabel.agent.api.LlmInteraction
import com.embabel.agent.api.LlmOperations
import com.embabel.agent.api.common.InteractionId
import com.embabel.agent.api.common.support.streaming.StreamingCapabilityDetector.supportsStreaming
import com.embabel.agent.spi.support.springai.ChatClientLlmOperations
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.util.loggerFor
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Utility for detecting actual streaming capability in ChatModel implementations.
 *
 * Spring AI's ChatModel interface extends StreamingChatModel, but not all implementations
 * provide meaningful streaming support. Some may throw UnsupportedOperationException,
 * return empty Flux, or provide stub implementations.
 *
 * This detector performs lightweight behavioral testing to determine if a model
 * actually supports streaming operations.
 */
internal object StreamingCapabilityDetector {
    private val logger = loggerFor<StreamingCapabilityDetector>()
    private val capabilityCache = ConcurrentHashMap<Class<*>, Boolean>()

    private const val CACHE_MISS_LOG_MESSAGE = "Cache miss for {}, testing streaming capability..."
    private const val TEST_PROMPT_MESSAGE = "Say 'test' to confirm streaming works"

    /**
     * Tests whether the given ChatModel actually supports streaming operations.
     *
     * @param model The ChatModel to test
     * @return true if the model supports streaming, false otherwise
     */
    fun supportsStreaming(model: ChatModel): Boolean {
        // Cache by model class to avoid repeated tests
        //return true
        return capabilityCache.computeIfAbsent(model.javaClass) {
            logger.debug(CACHE_MISS_LOG_MESSAGE, model.javaClass.simpleName)
            testStreamingCapability(model)
        }
    }

    /**
     * Delegates to [supportsStreaming]
     */
    fun supportsStreaming(llmOperations: LlmOperations, llmOptions: LlmOptions): Boolean {

        // Level 1 sanity check
        if (llmOperations !is ChatClientLlmOperations) return false

        // Level 2: Must have actual streaming capability
        val llm = llmOperations.getLlm(
            LlmInteraction(                 //  check for circular dependency
                id = InteractionId("capability-check"),
                llm = llmOptions
            )
        )

        return supportsStreaming(llm.model)

    }

    private fun testStreamingCapability(model: ChatModel): Boolean {
        return try {
            // Use a prompt that should generate a response
            val testRequest = Prompt(listOf(UserMessage(TEST_PROMPT_MESSAGE)))
            val stream = model.stream(testRequest)

            // Test if stream can be consumed without errors
            canConsumeStream(stream)
            true

        } catch (e: UnsupportedOperationException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun canConsumeStream(stream: Flux<ChatResponse>): Boolean {
        return try {
            // Test if we can check stream elements without consuming
            stream.hasElements()
                .timeout(Duration.ofMillis(100)) // configure
                .block()

            // If we get here without exceptions, streaming capability exists

            true

        } catch (e: Exception) {
            false // Any exception means streaming doesn't work
        }
    }
}
