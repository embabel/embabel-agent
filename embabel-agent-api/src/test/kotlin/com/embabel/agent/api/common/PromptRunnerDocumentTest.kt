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

import com.embabel.agent.api.common.support.OperationContextPromptRunner
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for Kotlin PromptRunner document shortcuts.
 */
class PromptRunnerDocumentTest {

    @TempDir
    lateinit var tempDir: Path

    @Nested
    inner class DocumentShortcutTests {

        @Test
        fun `withDocument adds user message with document part`() {
            val promptRunner = createTestPromptRunner()
            val document = AgentDocument.create("application/pdf", byteArrayOf(1, 2, 3), "report.pdf")

            val result = promptRunner.withDocument(document)

            assertThat(result.images).isEmpty()
            assertThat(result.messages).hasSize(1)

            val message = result.messages.single() as UserMessage
            assertThat(message.documentParts).hasSize(1)
            assertThat(message.documentParts[0].mimeType).isEqualTo("application/pdf")
            assertThat(message.documentParts[0].data).containsExactly(1, 2, 3)
            assertThat(message.documentParts[0].filename).isEqualTo("report.pdf")
        }

        @Test
        fun `withDocument from path adds user message with detected document part`() {
            val promptRunner = createTestPromptRunner()
            val path = tempDir.resolve("report.pdf")
            Files.write(path, byteArrayOf(4, 5, 6))

            val result = promptRunner.withDocument(path)

            assertThat(result.images).isEmpty()
            assertThat(result.messages).hasSize(1)

            val message = result.messages.single() as UserMessage
            assertThat(message.documentParts).hasSize(1)
            assertThat(message.documentParts[0].mimeType).isEqualTo("application/pdf")
            assertThat(message.documentParts[0].data).containsExactly(4, 5, 6)
            assertThat(message.documentParts[0].filename).isEqualTo("report.pdf")
        }

        @Test
        fun `withDocument is additive with existing messages`() {
            val promptRunner = createTestPromptRunner()
                .withMessage(UserMessage("Existing message"))

            val result = promptRunner.withDocument("text/csv", byteArrayOf(1, 2, 3), "data.csv")

            assertThat(result.messages).hasSize(2)
            assertThat(result.messages[0].content).isEqualTo("Existing message")

            val documentMessage = result.messages[1] as UserMessage
            assertThat(documentMessage.documentParts).hasSize(1)
            assertThat(documentMessage.documentParts[0].mimeType).isEqualTo("text/csv")
            assertThat(documentMessage.documentParts[0].filename).isEqualTo("data.csv")
        }
    }

    private fun createTestPromptRunner(): OperationContextPromptRunner {
        val mockOperationContext = mockk<OperationContext>(relaxed = true)

        return OperationContextPromptRunner(
            context = mockOperationContext,
            llm = LlmOptions(),
            toolGroups = emptySet(),
            toolObjects = emptyList(),
            promptContributors = emptyList(),
            contextualPromptContributors = emptyList(),
            generateExamples = null
        )
    }
}
