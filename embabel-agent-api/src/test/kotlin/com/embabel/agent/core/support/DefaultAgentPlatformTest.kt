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
package com.embabel.agent.core.support

import com.embabel.agent.api.dsl.evenMoreEvilWizard
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.ContextId
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.event.AgenticEventListener
import com.embabel.agent.spi.Context
import com.embabel.agent.spi.ContextRepository
import com.embabel.agent.spi.support.InMemoryContextRepository
import com.embabel.agent.spi.support.SimpleContext
import com.embabel.agent.support.Dog
import com.embabel.agent.testing.common.EventSavingAgenticEventListener
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DefaultAgentPlatformTest {

    private fun raw(
        l: AgenticEventListener = EventSavingAgenticEventListener(),
        contextRepository: ContextRepository = InMemoryContextRepository(),
    ): AgentPlatform {
        return DefaultAgentPlatform(
            "name",
            "description",
            mockk(),
            mockk(relaxed = true),
            l,
            contextRepository = contextRepository,
            ragService = mockk(),
            asyncer = mockk(),
            objectMapper = mockk(),
            outputChannel = mockk(),
            templateRenderer = mockk(),
        )
    }

    @Test
    fun `starts with empty blackboard`() {
        val dap = raw()
        val ap = dap.createAgentProcess(evenMoreEvilWizard(), ProcessOptions(), emptyMap())
        assertEquals(0, ap.objects.size)
    }

    @Test
    fun `binds parameters to blackboard`() {
        val dap = raw()
        val ap = dap.createAgentProcess(
            evenMoreEvilWizard(), ProcessOptions(), mapOf(
                "dog" to Dog("Duke")
            )
        )
        assertEquals(1, ap.objects.size)
        assertEquals("Duke", ((ap["dog"] as Dog).name))
    }

    @Nested
    inner class ContextLoading {

        @Test
        fun `loads context`() {
            val contextRepository = InMemoryContextRepository()
            var context: Context = SimpleContext()
            context.bind("otherDog", Dog("Apollo"))
            context = contextRepository.save(context)
            val dap = raw(contextRepository = contextRepository)
            val ap = dap.createAgentProcess(
                evenMoreEvilWizard(),
                ProcessOptions(contextId = ContextId(context.id!!)),
                mapOf(
                    "dog" to Dog("Duke")
                ),
            )
            assertEquals(2, ap.objects.size, "Should have 2 objects, not ${ap.objects.size}: ${ap.objects}")
            assertEquals("Duke", ((ap["dog"] as Dog).name))
            assertEquals("Apollo", ((ap["otherDog"] as Dog).name))
        }

    }

}
