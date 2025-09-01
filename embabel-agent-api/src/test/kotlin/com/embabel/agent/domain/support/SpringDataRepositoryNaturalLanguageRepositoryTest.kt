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
package com.embabel.agent.domain.support

import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.experimental.domain.persistence.FindEntitiesRequest
import com.embabel.agent.experimental.domain.persistence.support.FinderInvocation
import com.embabel.agent.experimental.domain.persistence.support.FinderInvocations
import com.embabel.agent.experimental.domain.persistence.support.naturalLanguageRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.data.repository.CrudRepository
import java.util.*
import kotlin.test.assertTrue

data class Thing(
    val name: String,
    val description: String,
    val count: Int = 0,
)

interface ThingRepository : CrudRepository<Thing, String> {

    fun findByName(name: String): Thing?

    fun findByDescription(description: String): Optional<Thing>

    fun findByNameAndDescription(
        name: String,
        description: String,
    ): Thing?

    fun findByNameAndCount(
        name: String,
        count: Int,
    ): Thing?
}

class SpringDataRepositoryNaturalLanguageRepositoryTest {

    @Test
    fun `no eligible fields`() {
        val tr = mockk<ThingRepository>()
        val mockPromptRunner = mockk<PromptRunner>()
        val promptSlot = slot<String>()
        val result = FinderInvocations(
            invocations = emptyList(),
        )
        every {
            mockPromptRunner.createObject(
                capture(promptSlot),
                FinderInvocations::class.java,
            )
        } answers { result }
        val mockPayload = mockk<ActionContext>()
        every { mockPayload.promptRunner(any()) } returns mockPromptRunner
        val nlr = tr.naturalLanguageRepository(
            context = mockPayload,
            llm = mockk(),
            idGetter = { it.name },
        )
        val found = nlr.find(FindEntitiesRequest("Description"))
        assertTrue(found.matches.isEmpty(), "Should not find anything")
    }

    @Test
    fun `returns nothing with nullable finder`() {
        val tr = mockk<ThingRepository>()
        every {
            tr.findByName(any())
        } returns null
        val mockPromptRunner = mockk<PromptRunner>()
        val promptSlot = slot<String>()
        val result = FinderInvocations(
            invocations = listOf(FinderInvocation(name = "findByName", args = listOf("something"))),
        )
        every {
            mockPromptRunner.createObject(
                capture(promptSlot),
                FinderInvocations::class.java,
            )
        } answers { result }
        val mockPayload = mockk<ActionContext>()
        every { mockPayload.promptRunner(any()) } returns mockPromptRunner
        val nlr = tr.naturalLanguageRepository(
            context = mockPayload,
            llm = mockk(),
            idGetter = { it.name }
        )
        val found = nlr.find(FindEntitiesRequest("Description"))
        assertTrue(found.matches.isEmpty(), "Should not find anything")
//        verify(exactly = 1) {
//            tr.findByName(result.fields[0].name)
//        }
    }

    @Test
    fun `returns nothing with Optional finder`() {
        val tr = mockk<ThingRepository>()
        every {
            tr.findByDescription("something")
        } returns Optional.empty()
        val mockPromptRunner = mockk<PromptRunner>()
        val promptSlot = slot<String>()
        val result = FinderInvocations(
            invocations = listOf(
                FinderInvocation(
                    name = "findByDescription",
                    args = listOf("something")
                )
            ),
        )
        every {
            mockPromptRunner.createObject(
                capture(promptSlot),
                FinderInvocations::class.java,
            )
        } answers { result }
        val mockPayload = mockk<ActionContext>()
        every { mockPayload.promptRunner(any()) } returns mockPromptRunner
        val nlr = tr.naturalLanguageRepository(
            context = mockPayload,
            llm = mockk(),
            idGetter = { it.name }
        )
        val found = nlr.find(FindEntitiesRequest("Description"))
        assertTrue(found.matches.isEmpty(), "Should not find anything")
//        verify(exactly = 1) {
//            tr.findByName(result.fields[0].name)
//        }
    }

    @Test
    fun `returns something with nullable finder`() {
        val theThing = Thing("something", "Description")
        val tr = mockk<ThingRepository>()
        every {
            tr.findByName("something")
        } returns theThing
        every {
            tr.findByName("Something")
        } returns null
        val mockPromptRunner = mockk<PromptRunner>()
        val promptSlot = slot<String>()
        val result = FinderInvocations(
            invocations = listOf(FinderInvocation(name = "findByName", args = listOf("something"))),
        )
        every {
            mockPromptRunner.createObject(
                capture(promptSlot),
                FinderInvocations::class.java,
            )
        } answers { result }
        val mockPayload = mockk<ActionContext>()
        every { mockPayload.promptRunner(any()) } returns mockPromptRunner
        val nlr = tr.naturalLanguageRepository(
            context = mockPayload,
            llm = mockk(),
            idGetter = { it.name }
        )
        val matches = nlr.find(FindEntitiesRequest("Description"))
        val found = matches.matches.single()
        assertEquals(theThing, found.match, "Should find the thing")
//        verify(exactly = 1) {
//            tr.findByName(result.fields[0].name)
//        }
    }

    @Test
    fun `returns something with nullable finder and alternate case`() {
        val theThing = Thing("Something", "Description")
        val tr = mockk<ThingRepository>()
        every {
            tr.findByName("Something")
        } returns theThing
        every {
            tr.findByName("something")
        } returns null
        val mockPromptRunner = mockk<PromptRunner>()
        val promptSlot = slot<String>()
        val result = FinderInvocations(
            invocations = listOf(
                FinderInvocation(name = "findByName", args = listOf("something")),
                FinderInvocation(name = "findByName", args = listOf("Something")),
            ),
        )
        every {
            mockPromptRunner.createObject(
                capture(promptSlot),
                FinderInvocations::class.java,
            )
        } answers { result }
        val mockPayload = mockk<ActionContext>()
        every { mockPayload.promptRunner(any()) } returns mockPromptRunner
        val nlr = tr.naturalLanguageRepository(
            context = mockPayload,
            llm = mockk(),
            idGetter = { it.name }
        )
        val matches = nlr.find(FindEntitiesRequest("Description"))
        val found = matches.matches.single()
        assertEquals(theThing, found.match, "Should find the thing")
//        verify(exactly = 1) {
//            tr.findByName(result.fields[0].name)
//        }
    }

    @Test
    fun `handles non-string parameter`() {
        val theThing = Thing("something", "Description", count = 1)
        val tr = mockk<ThingRepository>()
        every {
            tr.findByNameAndCount("something", 1)
        } returns theThing
        every {
            tr.findByNameAndCount("Something", 1)
        } returns null
        val mockPromptRunner = mockk<PromptRunner>()
        val promptSlot = slot<String>()
        val result = FinderInvocations(
            invocations = listOf(
                FinderInvocation(
                    name = "findByNameAndCount",
                    args = listOf("something", 1)
                )
            ),
        )
        every {
            mockPromptRunner.createObject(
                capture(promptSlot),
                FinderInvocations::class.java,
            )
        } answers { result }
        val mockPayload = mockk<ActionContext>()
        every { mockPayload.promptRunner(any()) } returns mockPromptRunner
        val nlr = tr.naturalLanguageRepository(
            context = mockPayload,
            llm = mockk(),
            idGetter = { it.name }
        )
        val matches = nlr.find(FindEntitiesRequest("Description"))
        val found = matches.matches.single()
    }

}
