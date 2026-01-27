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
package com.embabel.agent.api.common.nested.support

import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.api.common.support.OperationContextPromptRunner
import com.embabel.agent.api.common.withProperties
import com.embabel.agent.api.common.withoutProperties
import com.embabel.agent.test.unit.FakeOperationContext
import com.embabel.common.ai.model.LlmOptions
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PromptRunnerCreatingTest {

    internal inline fun <reified T> createPromptRunnerCreatingWithDefaults(): PromptRunner.Creating<T> {
        return PromptRunnerCreating(
            promptRunner = OperationContextPromptRunner(
                context = FakeOperationContext(),
                llm = LlmOptions(),
                toolGroups = emptySet(),
                toolObjects = emptyList(),
                promptContributors = emptyList(),
                contextualPromptContributors = emptyList(),
                generateExamples = false,
            ),
            outputClass = T::class.java,
            objectMapper = jacksonObjectMapper(),
        )
    }


    @Nested
    inner class WithPropertyFilter {

        @Test
        fun `test property filter`() {
            val creator = createPromptRunnerCreatingWithDefaults<Any>()
                .withPropertyFilter { it == "name" || it == "age" } as PromptRunnerCreating<Any>

            val promptRunner = creator.promptRunner

            assertTrue(promptRunner.propertyFilter.test("name"))
            assertTrue(promptRunner.propertyFilter.test("age"))
            assertFalse(promptRunner.propertyFilter.test("email"))
        }

        @Test
        fun `test chain multiple property filters`() {
            val creator = createPromptRunnerCreatingWithDefaults<Any>()
                .withPropertyFilter { it == "name" || it == "age" || it == "email" }
                .withPropertyFilter { it != "email" } as PromptRunnerCreating<Any>

            val promptRunner = creator.promptRunner

            assertTrue(promptRunner.propertyFilter.test("name"))
            assertTrue(promptRunner.propertyFilter.test("age"))
            assertFalse(promptRunner.propertyFilter.test("email"))
            assertFalse(promptRunner.propertyFilter.test("address"))
        }

        @Test
        fun `test default filter`() {
            val creator = createPromptRunnerCreatingWithDefaults<Any>() as PromptRunnerCreating<Any>

            val promptRunner = creator.promptRunner

            assertTrue(promptRunner.propertyFilter.test("name"))
            assertTrue(promptRunner.propertyFilter.test("age"))
            assertTrue(promptRunner.propertyFilter.test("email"))
            assertTrue(promptRunner.propertyFilter.test("anyProperty"))
        }
    }


    @Nested
    inner class WithProperties {

        @Test
        fun `test varargs syntax`() {
            val creator = createPromptRunnerCreatingWithDefaults<Any>()
                .withProperties("name", "age") as PromptRunnerCreating<Any>

            val promptRunner = creator.promptRunner

            assertTrue(promptRunner.propertyFilter.test("name"))
            assertTrue(promptRunner.propertyFilter.test("age"))
            assertFalse(promptRunner.propertyFilter.test("email"))
            assertFalse(promptRunner.propertyFilter.test("address"))
        }

        @Test
        fun `test KProperty syntax`() {
            val creator = createPromptRunnerCreatingWithDefaults<User>()
                .withProperties(User::name) as PromptRunnerCreating<User>

            val promptRunner = creator.promptRunner

            assertTrue(promptRunner.propertyFilter.test("name"))
            assertFalse(promptRunner.propertyFilter.test("age"))
        }

        @Test
        fun `test chain with withoutProperties`() {
            val creator = createPromptRunnerCreatingWithDefaults<Any>()
                .withProperties("name", "age", "email")
                .withoutProperties("email") as PromptRunnerCreating<Any>

            val promptRunner = creator.promptRunner

            assertTrue(promptRunner.propertyFilter.test("name"))
            assertTrue(promptRunner.propertyFilter.test("age"))
            assertFalse(promptRunner.propertyFilter.test("email"))
            assertFalse(promptRunner.propertyFilter.test("address"))
        }

    }


    @Nested
    inner class WithoutProperties {

        @Test
        fun `test varargs syntax`() {
            val creator = createPromptRunnerCreatingWithDefaults<Any>()
                .withoutProperties("email", "address") as PromptRunnerCreating<Any>

            val promptRunner = creator.promptRunner

            assertTrue(promptRunner.propertyFilter.test("name"))
            assertTrue(promptRunner.propertyFilter.test("age"))
            assertFalse(promptRunner.propertyFilter.test("email"))
            assertFalse(promptRunner.propertyFilter.test("address"))
        }

        @Test
        fun `test KProperty syntax`() {
            val creator = createPromptRunnerCreatingWithDefaults<User>()
                .withoutProperties(User::name) as PromptRunnerCreating<User>

            val promptRunner = creator.promptRunner

            assertFalse(promptRunner.propertyFilter.test("name"))
            assertTrue(promptRunner.propertyFilter.test("age"))
        }

        @Test
        fun `test chain multiple`() {
            val creator = createPromptRunnerCreatingWithDefaults<Any>()
                .withoutProperties("email")
                .withoutProperties("address") as PromptRunnerCreating<Any>

            val promptRunner = creator.promptRunner

            assertTrue(promptRunner.propertyFilter.test("name"))
            assertTrue(promptRunner.propertyFilter.test("age"))
            assertFalse(promptRunner.propertyFilter.test("email"))
            assertFalse(promptRunner.propertyFilter.test("address"))
        }
    }

}


data class User(
    val name: String,
)
