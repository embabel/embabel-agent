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
package com.embabel.common.ai.model

import com.embabel.agent.core.AgentProcess
import com.embabel.agent.decision.DecisionCompletion
import com.embabel.agent.decision.DecisionExecutionContext
import com.embabel.agent.decision.DecisionInstrumentation
import com.embabel.agent.decision.DecisionModel
import com.embabel.agent.decision.DecisionObservation
import com.embabel.agent.decision.DecisionOutcome
import com.embabel.agent.decision.DecisionProvider
import com.embabel.agent.decision.DecisionProvenance
import com.embabel.agent.decision.DecisionRequest
import com.embabel.agent.decision.DecisionTelemetryEvent
import com.embabel.agent.decision.EvidenceKind
import com.embabel.agent.decision.RawAnswer
import com.embabel.agent.decision.RawDecisionOutcome
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.support.AgentProcessAccessor
import com.embabel.agent.spi.support.DecisionExecutionContextBridge
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class DecisionExecutionContextTest {

    private val llm = mockk<LlmService<*>>(relaxed = true) {
        every { name } returns "test-llm"
        every { provider } returns "test"
    }

    @AfterEach
    fun clearContext() {
        AgentProcess.remove()
        assertThat(ModelSelectionContextHolder.get()).isEqualTo(ModelSelectionContext.EMPTY)
    }

    @Test
    fun `registered models carry both contexts with absent noop and custom telemetry`() {
        val seen = ConcurrentLinkedQueue<Pair<AgentProcess?, ModelSelectionContext>>()
        val model = decision("registered") {
            seen += AgentProcess.get() to ModelSelectionContextHolder.get()
            success()
        }
        val provider = modelProvider(listOf(model))
        val process = mockk<AgentProcess>(relaxed = true)
        val selection = ModelSelectionContext("caller", ProviderCredential("test", "secret"))
        val custom = passthroughInstrumentation()

        assertThat(provider.getDecisionModel(ByNameModelSelectionCriteria("registered"))).isSameAs(model)
        assertThat(model.installExecutionContext(passthroughContext())).isFalse()
        AgentProcessAccessor.with(process) {
            ModelSelectionContextHolder.with(selection) {
                listOf(
                    model,
                    model.withInstrumentation(DecisionInstrumentation.noop()),
                    model.withInstrumentation(custom),
                ).forEach { configured ->
                    assertThat(configured.ask(yesNoRequest())).isInstanceOf(DecisionOutcome.Success::class.java)
                }
                assertThat(AgentProcess.get()).isSameAs(process)
                assertThat(ModelSelectionContextHolder.get()).isEqualTo(selection)
            }
        }

        assertThat(seen).hasSize(3)
        seen.forEach { (seenProcess, seenSelection) ->
            assertThat(seenProcess).isSameAs(process)
            assertThat(seenSelection).isEqualTo(selection)
        }
    }

    @Test
    fun `arbitrary pre-resolved model is installed without replacing its identity`() {
        val seen = ConcurrentLinkedQueue<ModelSelectionContext>()
        val arbitrary = decision("arbitrary") {
            seen += ModelSelectionContextHolder.get()
            success()
        }
        val provider = modelProvider(emptyList())
        val selection = ModelSelectionContext("pre-resolved")

        val resolved = provider.getDecisionModel(PreResolvedModelSelectionCriteria(arbitrary))

        assertThat(resolved).isSameAs(arbitrary)
        assertThat(arbitrary.installExecutionContext(passthroughContext())).isFalse()
        ModelSelectionContextHolder.with(selection) {
            assertThat(resolved.ask(yesNoRequest())).isInstanceOf(DecisionOutcome.Success::class.java)
        }
        assertThat(seen).containsExactly(selection)
    }

    @Test
    fun `bridge captures at wrap time and restores nested previous contexts after failure`() {
        val bridge = DecisionExecutionContextBridge()
        val capturedProcess = mockk<AgentProcess>(relaxed = true)
        val previousProcess = mockk<AgentProcess>(relaxed = true)
        val capturedSelection = ModelSelectionContext("captured")
        val previousSelection = ModelSelectionContext("previous")
        lateinit var wrapped: Callable<String>

        AgentProcessAccessor.with(capturedProcess) {
            ModelSelectionContextHolder.with(capturedSelection) {
                wrapped = bridge.wrap(Callable {
                    assertThat(AgentProcess.get()).isSameAs(capturedProcess)
                    assertThat(ModelSelectionContextHolder.get()).isEqualTo(capturedSelection)
                    throw IOException("sentinel")
                })
            }
        }

        AgentProcessAccessor.with(previousProcess) {
            ModelSelectionContextHolder.with(previousSelection) {
                assertThatThrownBy { wrapped.call() }.isInstanceOf(IOException::class.java)
                assertThat(AgentProcess.get()).isSameAs(previousProcess)
                assertThat(ModelSelectionContextHolder.get()).isEqualTo(previousSelection)
            }
        }
    }

    @Test
    fun `worker context is restored after provider failure and reuse`() {
        val calls = AtomicInteger()
        val threads = ConcurrentLinkedQueue<Thread>()
        val processes = Collections.synchronizedList(mutableListOf<AgentProcess?>())
        val selections = ConcurrentLinkedQueue<ModelSelectionContext>()
        val model = decision("reused") {
            threads += Thread.currentThread()
            processes += AgentProcess.get()
            selections += ModelSelectionContextHolder.get()
            if (calls.getAndIncrement() == 0) throw IllegalStateException("sentinel-provider")
            success()
        }
        modelProvider(listOf(model))
        val process = mockk<AgentProcess>(relaxed = true)
        val selection = ModelSelectionContext("first")

        val first = AgentProcessAccessor.with(process) {
            ModelSelectionContextHolder.with(selection) { model.ask(yesNoRequest()) }
        }
        val second = model.ask(yesNoRequest())

        assertThat(first).isInstanceOf(DecisionOutcome.Failure::class.java)
        assertThat(second).isInstanceOf(DecisionOutcome.Success::class.java)
        assertThat(threads).hasSize(2)
        assertThat(threads.elementAt(1)).isSameAs(threads.elementAt(0))
        assertThat(processes.elementAt(0)).isSameAs(process)
        assertThat(processes.elementAt(1)).isNull()
        assertThat(selections).containsExactly(selection, ModelSelectionContext.EMPTY)
        assertThat(calls.get()).isEqualTo(2)
    }

    private fun modelProvider(decisions: List<DecisionModel>) = ConfigurableModelProvider(
        llms = listOf(llm),
        embeddingServices = emptyList(),
        properties = ConfigurableModelProviderProperties(defaultLlm = "test-llm"),
        decisionModels = decisions,
    )

    private fun decision(name: String, provider: DecisionProvider): DecisionModel =
        DecisionModel(provider).named(name, "custom")

    private fun passthroughContext() = object : DecisionExecutionContext {
        override fun <T> wrap(work: Callable<T>): Callable<T> = work
    }

    private fun passthroughInstrumentation() = DecisionInstrumentation {
        object : DecisionObservation {
            override fun <T> wrap(work: Callable<T>): Callable<T> = work
            override fun event(event: DecisionTelemetryEvent) = Unit
            override fun complete(completion: DecisionCompletion) = Unit
            override fun close() = Unit
        }
    }

    private fun yesNoRequest(): DecisionRequest = DecisionRequest.builder().also {
        it.yesNo("yes", "yes?")
    }.build()

    private fun success(): RawDecisionOutcome = RawDecisionOutcome.success(
        listOf(RawAnswer.yesNo("yes", 1.0, "true")),
        DecisionProvenance.builder("sentinel", EvidenceKind.DISTRIBUTION).build(),
    )
}
