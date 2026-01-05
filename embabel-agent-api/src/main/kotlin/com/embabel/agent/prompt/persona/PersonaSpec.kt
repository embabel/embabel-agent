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
package com.embabel.agent.prompt.persona

import com.embabel.common.ai.prompt.PromptContributor
import com.fasterxml.jackson.databind.annotation.JsonDeserialize

/**
 * Interface for Persona, when we can't use data class directly
 * (e.g. in some persistence scenarios).
 */
@JsonDeserialize(`as` = Persona::class)
interface PersonaSpec : PromptContributor {

    val name: String
    val persona: String
    val voice: String
    val objective: String

    override fun contribution(): String {
        return """
            You are $name.
            Your persona: $persona.
            Your objective is $objective.
            Your voice: $voice.
        """.trimIndent()
    }

    companion object {

        @JvmStatic
        fun create(
            name: String,
            persona: String,
            voice: String,
            objective: String
        ): PersonaSpec = Persona(
            name,
            persona,
            voice,
            objective,
        )

        operator fun invoke(
            name: String,
            persona: String,
            voice: String,
            objective: String
        ): PersonaSpec = Persona(
            name,
            persona,
            voice,
            objective
        )

    }
}

/**
 * A way to structure LLM responses, by grounding them
 * in a personality.
 */
data class Persona(
    override val name: String,
    override val persona: String,
    override val voice: String,
    override val objective: String
) : PersonaSpec
