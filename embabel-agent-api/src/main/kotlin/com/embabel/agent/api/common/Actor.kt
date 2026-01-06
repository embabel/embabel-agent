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
package com.embabel.agent.api.common

import com.embabel.agent.prompt.persona.Instruction
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.prompt.PromptContributor
import org.springframework.validation.annotation.Validated

/**
 * Core abstraction for an LLM with tools and a persona.
 * An Actor plays a role: Convenient way to combine a PromptContributor
 * with an LLM and tools.
 * Open to allow subclasses to add tools via @Tool methods
 */
@Validated
open class Actor<T : PromptContributor> @JvmOverloads constructor(
    val persona: T,
    val llm: LlmOptions,
    val toolGroups: Set<String> = emptySet(),
) : PromptContributor by persona {

    /**
     * Construct an Actor with a simple instruction as persona.
     */
    @JvmOverloads
    constructor(
        instruction: String,
        llm: LlmOptions,
        toolGroups: Set<String> = emptySet(),
    ) : this(
        persona = Instruction(instruction) as T,
        llm = llm,
        toolGroups = toolGroups,
    )

    /**
     * Return a PromptRunner configured with this Actor's persona, LLM, and tools.
     * The caller can continue to customize this PromptRunner before using it
     * to create objects or generate text.
     */
    fun promptRunner(ai: Ai): PromptRunner {
        return ai.withLlm(llm)
            .withPromptContributor(persona)
            .withToolGroups(toolGroups)
            .withToolObject(this)
    }

    /**
     * Return a PromptRunner configured with this Actor's persona, LLM, and tools.
     * The caller can continue to customize this PromptRunner before using it
     * to create objects or generate text.
     */
    fun promptRunner(context: OperationContext) = promptRunner(context.ai())

    override fun toString(): String = "Actor(persona=${persona}, llm=$llm, toolGroups=$toolGroups)"

}
