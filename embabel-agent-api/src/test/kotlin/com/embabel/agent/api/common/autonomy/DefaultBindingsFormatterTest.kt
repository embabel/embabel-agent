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
package com.embabel.agent.api.common.autonomy

import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.core.types.HasInfoString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class DefaultBindingsFormatterTest {

    private val formatter = DefaultBindingsFormatter()

    @Test
    @DisplayName("format returns empty string for empty bindings")
    fun formatEmptyBindings() {
        val result = formatter.format(emptyMap())
        assertEquals("", result)
    }

    @Test
    @DisplayName("format uses PromptContributor.contribution() when implemented")
    fun formatPromptContributor() {
        val contributor = object : PromptContributor {
            override fun contribution(): String = "prompt contribution content"
        }

        val result = formatter.format(mapOf("input" to contributor))

        assertEquals("prompt contribution content", result)
    }

    @Test
    @DisplayName("format uses HasInfoString.infoString() when implemented")
    fun formatHasInfoString() {
        val hasInfo = object : HasInfoString {
            override fun infoString(verbose: Boolean?, indent: Int): String {
                return "info string with verbose=$verbose indent=$indent"
            }
        }

        val result = formatter.format(mapOf("input" to hasInfo))

        assertEquals("info string with verbose=true indent=0", result)
    }

    @Test
    @DisplayName("format uses toString() for plain objects")
    fun formatPlainObject() {
        data class Person(val name: String, val age: Int)

        val person = Person("Alice", 30)

        val result = formatter.format(mapOf("person" to person))

        assertEquals("Person(name=Alice, age=30)", result)
    }

    @Test
    @DisplayName("format uses toString() for strings")
    fun formatString() {
        val result = formatter.format(mapOf("intent" to "find horoscope"))

        assertEquals("find horoscope", result)
    }

    @Test
    @DisplayName("format joins multiple bindings with newlines")
    fun formatMultipleBindings() {
        val contributor = object : PromptContributor {
            override fun contribution(): String = "from prompt"
        }
        val hasInfo = object : HasInfoString {
            override fun infoString(verbose: Boolean?, indent: Int): String = "from info"
        }

        // Using LinkedHashMap to preserve order
        val bindings = linkedMapOf<String, Any>(
            "a" to contributor,
            "b" to hasInfo,
            "c" to "plain string"
        )

        val result = formatter.format(bindings)

        assertEquals("from prompt\nfrom info\nplain string", result)
    }

    @Test
    @DisplayName("format prefers PromptContributor over HasInfoString when both implemented")
    fun formatPrefersPromptContributor() {
        val both = object : PromptContributor, HasInfoString {
            override fun contribution(): String = "from prompt contributor"
            override fun infoString(verbose: Boolean?, indent: Int): String = "from has info"
        }

        val result = formatter.format(mapOf("input" to both))

        assertEquals("from prompt contributor", result)
    }
}
