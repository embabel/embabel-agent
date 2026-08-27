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
package com.embabel.agent.api.template

import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.PlatformServices
import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.core.AgentPlatform
import com.embabel.common.textio.template.NoSuchTemplateException
import com.embabel.common.textio.template.TemplateRenderer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TemplatedPromptRunnerBuilderTest {

    private val context = mockk<OperationContext>()
    private val platform = mockk<AgentPlatform>()
    private val platformServices = mockk<PlatformServices>()
    private val renderer = mockk<TemplateRenderer>()
    private val runner = mockk<PromptRunner>()
    private val runnerWithSystemPrompt = mockk<PromptRunner>()

    private fun stubPlatformRenderer() {
        every { context.agentPlatform() } returns platform
        every { platform.platformServices } returns platformServices
        every { platformServices.templateRenderer } returns renderer
    }

    @Test
    fun `renders template via platform renderer and passes result as system prompt`() {
        stubPlatformRenderer()
        val model = mapOf<String, Any>("company" to "Acme")
        every { renderer.renderLoadedTemplate("greeting", model) } returns "You are the Acme assistant"
        every { runner.withSystemPrompt("You are the Acme assistant") } returns runnerWithSystemPrompt

        val result = TemplatedPromptRunnerBuilder(context, runner).withSystemPromptTemplate("greeting", model)

        assertEquals(runnerWithSystemPrompt, result)
        verify { runner.withSystemPrompt("You are the Acme assistant") }
    }

    @Test
    fun `defaults to an empty model for static templates`() {
        stubPlatformRenderer()
        every { renderer.renderLoadedTemplate("static", emptyMap()) } returns "You are a helpful assistant"
        every { runner.withSystemPrompt("You are a helpful assistant") } returns runnerWithSystemPrompt

        val result = TemplatedPromptRunnerBuilder(context, runner).withSystemPromptTemplate("static")

        assertEquals(runnerWithSystemPrompt, result)
    }

    @Test
    fun `propagates NoSuchTemplateException for an unknown template`() {
        stubPlatformRenderer()
        every { renderer.renderLoadedTemplate("missing", emptyMap()) } throws NoSuchTemplateException("missing")

        assertThrows<NoSuchTemplateException> {
            TemplatedPromptRunnerBuilder(context, runner).withSystemPromptTemplate("missing")
        }
    }
}
