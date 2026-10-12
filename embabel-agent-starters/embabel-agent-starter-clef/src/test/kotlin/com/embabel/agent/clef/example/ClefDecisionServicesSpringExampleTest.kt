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
package com.embabel.agent.clef.example

import com.embabel.agent.autoconfigure.models.clef.AgentClefAutoConfiguration
import com.embabel.agent.autoconfigure.platform.DecisionModelServicesAutoConfiguration
import com.embabel.agent.autoconfigure.platform.DecisionServiceRegistryAutoConfiguration
import com.embabel.agent.clef.*
import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.common.RetryProperties
import com.embabel.agent.spi.decision.LlmDecisionServiceFactory
import com.embabel.agent.typesafe.TypeSafeModelFactory
import com.embabel.common.ai.classification.*
import com.embabel.common.ai.decision.*
import com.embabel.common.ai.model.*
import io.micrometer.observation.*
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.retry.support.RetryTemplate
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

class ClefDecisionServicesSpringExampleTest {
    @Test
    fun `three real provider factories coexist through named default role and classification selectors`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val calls = AtomicInteger()
        server.createContext("/v1/systemone") { exchange ->
            calls.incrementAndGet(); exchange.requestBody.readAllBytes()
            exchange.responseHeaders.add("X-Embabel-Clef-Provenance", PROVENANCE)
            val body = """{"model":"clef-text-0.6b-coreml","answers":{"q001":{"type":"choice","choice":"billing","confidence":0.75,"probabilities":{"billing":0.75,"support":0.25}}},"usage":{"input_tokens":80,"output_tokens":0}}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }; exchange.close()
        }
        server.start()
        try {
            val operations = mockk<LlmOperations>()
            val models = mockk<ModelProvider>()
            val llm = mockk<LlmService<*>>()
            every { llm.name } returns "gpt-example"
            every { llm.provider } returns "test-llm"
            every { models.getLlm(any()) } returns llm
            val retry = mockk<RetryProperties>()
            every { retry.retryTemplate(any()) } returns RetryTemplate()
            every { retry.propertyPrefix } returns "example"
            val observations = ObservationRegistry.create()
            val names = mutableListOf<String>()
            observations.observationConfig().observationHandler(object : ObservationHandler<Observation.Context> {
                override fun supportsContext(context: Observation.Context) = true
                override fun onStop(context: Observation.Context) { names += context.name }
            })
            val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                AgentClefAutoConfiguration::class.java, DecisionModelServicesAutoConfiguration::class.java,
                DecisionServiceRegistryAutoConfiguration::class.java))
                .withBean("observations", ObservationRegistry::class.java, { observations })
                .withBean("typeSafeFactory", TypeSafeModelFactory::class.java, { TypeSafeModelFactory(Supplier { error("No credential lookup during construction") }) })
                .withBean("llmFactory", LlmDecisionServiceFactory::class.java, { LlmDecisionServiceFactory(operations, models, retry, observations) })
                // tag::clef-properties[]
                .withPropertyValues(
                    "embabel.agent.platform.models.clef.enabled=true",
                    "embabel.agent.platform.models.clef.base-url=http://127.0.0.1:${server.address.port}",
                    "embabel.agent.platform.decisions.services.local.provider=clef",
                    "embabel.agent.platform.decisions.services.local.model=clef-text-0.6b-coreml",
                    "embabel.agent.platform.decisions.services.jev.provider=typesafe",
                    "embabel.agent.platform.decisions.services.jev.model=jev-latest",
                    "embabel.agent.platform.decisions.services.review.provider=llm",
                    "embabel.agent.platform.decisions.services.review.model=gpt-example",
                    "embabel.models.decision.default=local",
                    "embabel.models.decision.roles.triage=local",
                    "embabel.models.classification.default=jev",
                    "embabel.models.classification.roles.review=review",
                )
                // end::clef-properties[]
            runner.run { context ->
                assertNull(context.startupFailure)
                assertEquals(0, calls.get())
                val registry = context.getBean(DecisionServiceRegistry::class.java)
                assertEquals("TypeSafe", registry.decisions().named("jev").provider)
                assertEquals("test-llm", registry.classifications().byRole("review").provider)
                assertEquals("CLEF", registry.decisions().byRole("triage").provider)
                assertEquals("TypeSafe", registry.classifications().defaultService().provider)
                // tag::clef-ask[]
                val question = Questions.named("team").choice("Which team?")
                    .option("billing", "Payments").option("support", "Help").build()
                val response = registry.decisions().defaultService().ask("A payment failed", DecisionSpec.of(question))
                val outcome = response.answer(question) as ClassificationResult.Selected
                val distribution = response.choiceDistribution(question)!!
                // end::clef-ask[]
                assertEquals("billing", outcome.categoryId)
                assertEquals(listOf(0.75, 0.25), distribution.probabilities.map { it.probability })
                assertEquals(1, calls.get()); assertTrue(names.contains("embabel.ai.ask"))
            }
            verify { operations wasNot Called }
        } finally { server.stop(0) }
    }

    @Test
    fun `application service override never invokes a throwing configured CLEF factory`() {
        val factory = mockk<ClefModelFactory>()
        every { factory.close() } just Runs
        val replacement = mockk<DecisionService>()
        every { factory.id } throws IllegalStateException("Must not inspect overridden provider")
        ApplicationContextRunner().withConfiguration(AutoConfigurations.of(AgentClefAutoConfiguration::class.java,
            DecisionModelServicesAutoConfiguration::class.java))
            .withBean("clefFactory", ClefModelFactory::class.java, { factory })
            .withBean("local", DecisionService::class.java, { replacement })
            .withPropertyValues("embabel.agent.platform.decisions.services.local.provider=clef",
                "embabel.agent.platform.decisions.services.local.model=clef-text-0.6b-coreml")
            .run { context ->
                assertNull(context.startupFailure); assertSame(replacement, context.getBean("local"))
                verify { factory wasNot Called }
            }
        verify(exactly = 1) { factory.close() }
    }

    companion object {
        // Pinned native profile fixture, not cryptographic attestation of a checker binary.
        private const val PROVENANCE = """{"source_repo":"FluidInference/clef-text-0.6b-coreml","source_revision":"3c61a60954478ef385aa42d67d5764642dcc9135","variant":"local-single-function-L1024","license":"Apache-2.0","requested_compute":"cpu","asset_sha256":{"Decoder.mlpackage/Data/com.apple.CoreML/model.mlmodel":"49049d20da650ffa67b550ce0ac08f12aff305b4f9e6bbfe6dbc8e46408ab2e1","Head.mlpackage/Data/com.apple.CoreML/model.mlmodel":"71045bc150f3091142578e40bde13f7db5b3435d127af7f1c7dda6b637a31ec4","Decoder.mlpackage/Data/com.apple.CoreML/weights/weight.bin":"cbe7a6f22befa4edee09e7c806b2b0b9db8533d1ede737540fa82a0261435742","Head.mlpackage/Data/com.apple.CoreML/weights/weight.bin":"d028526027d6ce12313fbc80ad35f148ecb815f09f83695c5bb2b5872dbd4657","config.json":"af356b87b840722b77068df83612d6ad6efdd19804c6c955e4689a0e10c6bf58","tokenizer.json":"be75606093db2094d7cd20f3c2f385c212750648bd6ea4fb2bf507a6a4c55506","embeddings.f16":"616f9374ddbaae3a033a0174557d502719154882e21e5d975ab5b58e0244459d"},"checker_sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","checker_source_revision":"0230049c4e81f8de53ae1086b20cd9106c511105","checker_build_id":"test-build","checker_provenance":"operator-supplied source/build; digest identifies bytes, not origin","confidence_semantics":"selected_probability","protocol":"embabel-clef-systemone-v1","experimental":true}"""
    }
}
