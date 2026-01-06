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
import com.embabel.agent.core.ProcessContext
import com.embabel.common.ai.model.LlmOptions
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for PromptRunner image support
 */
class PromptRunnerImageTest {

    @Test
    fun `withImage adds image to PromptRunner`() {
        val promptRunner = createTestPromptRunner()
        val image = AgentImage.create("image/jpeg", byteArrayOf(1, 2, 3))

        val result = promptRunner.withImage(image)

        assertThat(result.images).hasSize(1)
        assertThat(result.images[0]).isEqualTo(image)
    }

    @Test
    fun `withImages varargs adds multiple images`() {
        val promptRunner = createTestPromptRunner()
        val image1 = AgentImage.create("image/jpeg", byteArrayOf(1, 2, 3))
        val image2 = AgentImage.create("image/png", byteArrayOf(4, 5, 6))

        val result = promptRunner.withImages(image1, image2)

        assertThat(result.images).hasSize(2)
        assertThat(result.images[0]).isEqualTo(image1)
        assertThat(result.images[1]).isEqualTo(image2)
    }

    @Test
    fun `withImages list adds multiple images`() {
        val promptRunner = createTestPromptRunner()
        val images = listOf(
            AgentImage.create("image/jpeg", byteArrayOf(1, 2, 3)),
            AgentImage.create("image/png", byteArrayOf(4, 5, 6)),
            AgentImage.create("image/gif", byteArrayOf(7, 8, 9))
        )

        val result = promptRunner.withImages(images)

        assertThat(result.images).hasSize(3)
        assertThat(result.images).containsExactlyElementsOf(images)
    }

    @Test
    fun `withImage is additive`() {
        val promptRunner = createTestPromptRunner()
        val image1 = AgentImage.create("image/jpeg", byteArrayOf(1, 2, 3))
        val image2 = AgentImage.create("image/png", byteArrayOf(4, 5, 6))

        val result = promptRunner
            .withImage(image1)
            .withImage(image2)

        assertThat(result.images).hasSize(2)
        assertThat(result.images[0]).isEqualTo(image1)
        assertThat(result.images[1]).isEqualTo(image2)
    }

    @Test
    fun `withImages can be combined`() {
        val promptRunner = createTestPromptRunner()
        val image1 = AgentImage.create("image/jpeg", byteArrayOf(1, 2, 3))
        val image2 = AgentImage.create("image/png", byteArrayOf(4, 5, 6))
        val image3 = AgentImage.create("image/gif", byteArrayOf(7, 8, 9))

        val result = promptRunner
            .withImage(image1)
            .withImages(image2, image3)

        assertThat(result.images).hasSize(3)
    }

    @Test
    fun `new PromptRunner starts with empty images list`() {
        val promptRunner = createTestPromptRunner()

        assertThat(promptRunner.images).isEmpty()
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
