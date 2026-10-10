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
package com.embabel.agent.api.tool.hitl

import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.spi.support.persistence.BlackboardEntrySerializerResolver
import com.embabel.agent.spi.support.persistence.InMemoryBlackboardSnapshotter
import com.embabel.agent.spi.support.persistence.JacksonBlackboardEntrySerializer
import com.embabel.common.util.EmbabelObjectMapperHolder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ToolCallConfirmationPersistenceTest {

    private val snapshotter = InMemoryBlackboardSnapshotter(
        BlackboardEntrySerializerResolver(
            serializers = emptyList(),
            fallback = JacksonBlackboardEntrySerializer(EmbabelObjectMapperHolder.createDefault().get()),
        )
    )

    @Test
    fun `proposal verdict and outcome survive a snapshot round trip`() {
        val blackboard = InMemoryBlackboard("bb-1")
        val proposal = ToolCallProposal(
            toolName = "create_task",
            arguments = """{"title":"Write spec"}""",
            message = "Create this task?",
        )
        val verdict = ToolCallVerdict(proposal.id, accepted = true, source = VerdictSource.UI, note = "yes")
        val outcome = ToolCallOutcome(proposal.id, executed = true)
        blackboard.addObject(proposal)
        blackboard.addObject(verdict)
        blackboard.addObject(outcome)

        val restored = snapshotter.restore(snapshotter.snapshot(blackboard))

        assertEquals(listOf(proposal, verdict, outcome), restored.objects)
    }
}
