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
package com.embabel.agent.openai

import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.SpringAiEmbeddingService
import com.openai.client.OpenAIClient
import com.openai.client.OpenAIClientAsync
import org.slf4j.Logger

internal class OpenAiClientOwnership(
    sync: OpenAIClient,
    async: OpenAIClientAsync,
    logger: Logger,
) : AutoCloseable {

    private var clientCleanup: Runnable? = Runnable {
        closeClient(logger) { sync.close() }
        closeClient(logger) { async.close() }
    }

    @Synchronized
    override fun close() {
        val cleanup = clientCleanup
        clientCleanup = null
        cleanup?.run()
    }

    fun buildChatService(build: () -> LlmService<*>): LlmService<*> =
        transferClients(build) { service, cleanup ->
            (service as SpringAiLlmService).registerChatModelCleanup(cleanup)
        }

    fun buildEmbeddingService(build: () -> EmbeddingService): EmbeddingService =
        transferClients(build) { service, cleanup ->
            ((service as SpringAiEmbeddingService).model as OpenAiCompatibleEmbeddingModel).registerCleanup(cleanup)
        }

    @Synchronized
    private fun <T> transferClients(build: () -> T, registerCleanup: (T, Runnable) -> Unit): T {
        val cleanup = checkNotNull(clientCleanup) { "OpenAI clients are no longer owned by this factory" }
        try {
            return build().also { service ->
                registerCleanup(service, cleanup)
                clientCleanup = null
            }
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }
}

internal fun closeClient(logger: Logger, close: () -> Unit) {
    runCatching(close).onFailure { logger.warn("Could not close an OpenAI client: {}", it.message) }
}
