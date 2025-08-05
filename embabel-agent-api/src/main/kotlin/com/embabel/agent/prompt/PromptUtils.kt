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
package com.embabel.agent.prompt

import com.embabel.common.util.DummyInstanceCreator
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

/**
 * Utility functions for building prompts.
 */
object PromptUtils {

    val dummyInstanceCreator = DummyInstanceCreator()

    val om: ObjectMapper = jacksonObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    /**
     * Generates a JSON example of the given class
     * with dummy data. Makes few shot examples easier to create.
     *
     * @param clazz The class to generate a JSON example for.
     */
    @JvmStatic
    fun jsonExampleOf(clazz: Class<*>): String {
        val dummy = dummyInstanceCreator.createDummyInstance(clazz)
        return om.writerWithDefaultPrettyPrinter().writeValueAsString(dummy)
    }

    /**
     * Generates a JSON example of the given class
     * with dummy data. Makes few shot examples easier to create.
     *
     * @param T The type to generate a JSON example for.
     */
    inline fun <reified T> jsonExampleOf(): String {
        return jsonExampleOf(T::class.java)
    }
}
