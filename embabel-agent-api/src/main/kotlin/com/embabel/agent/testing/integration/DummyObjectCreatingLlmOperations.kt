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
package com.embabel.agent.testing.integration

import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.event.LlmRequestEvent
import com.embabel.agent.spi.LlmInteraction
import com.embabel.agent.spi.LlmOperations
import com.embabel.chat.Message
import com.embabel.common.util.DummyInstanceCreator

/**
 * Fake LLM transformer that generates valid classes with random strings.
 */
open class DummyObjectCreatingLlmOperations(
    stringsToUse: List<String>,
) : LlmOperations, DummyInstanceCreator(stringsToUse) {

    override fun generate(
        prompt: String,
        interaction: LlmInteraction,
        agentProcess: AgentProcess,
        action: Action?,
    ): String {
        return stringsToUse.random()
    }

    override fun <O> doTransform(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?,
    ): O {
        logger.debug("Creating fake response for class: {}", outputClass.name)

        // Create a mock instance based on the output class structure
        @Suppress("UNCHECKED_CAST")
        return createDummyInstance(outputClass) as O
    }

    override fun <O> createObjectIfPossible(
        prompt: String,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?,
    ): Result<O> {
        logger.debug("Creating fake response for class: {}", outputClass.name)

        // Create a mock instance based on the output class structure
        @Suppress("UNCHECKED_CAST")
        val o = createDummyInstance(outputClass) as O

        // TODO simulate occasional failures
        return Result.success(o)
    }

    override fun <O> createObject(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?,
    ): O = doTransform(
        messages = messages,
        interaction = interaction,
        outputClass = outputClass,
        llmRequestEvent = null,
    )

    companion object {

        /**
         * A fake LLM transformer that generates Lorem Ipsum
         * style fake test
         */
        val LoremIpsum: LlmOperations = DummyObjectCreatingLlmOperations(
            LoremIpsums
        )
    }
}
