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
package com.embabel.agent.cloudflare.example

import com.embabel.agent.autoconfigure.models.cloudflare.AgentCloudflareAutoConfiguration
import com.embabel.agent.autoconfigure.platform.DecisionModelServicesAutoConfiguration
import com.embabel.agent.autoconfigure.platform.DecisionServiceRegistryAutoConfiguration
import com.embabel.agent.autoconfigure.platform.LlmDecisionServicesAutoConfiguration
import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.spi.common.RetryProperties
import com.embabel.agent.spi.decision.LlmDecisionServiceFactory
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.agent.typesafe.TypeSafeModelFactory
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.*
import com.embabel.common.ai.decision.cloudflare.CloudflareClientOptions
import com.embabel.common.ai.decision.cloudflare.CloudflareModelFactory
import com.embabel.common.ai.decision.support.StubDecisionService
import com.embabel.common.ai.model.DecisionServiceRegistry
import com.embabel.common.ai.model.ModelProvider
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.ai.chat.model.ChatModel
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

class CloudflareDecisionServicesSpringExampleTest {
    private val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
        AgentCloudflareAutoConfiguration::class.java,
        LlmDecisionServicesAutoConfiguration::class.java,
        DecisionModelServicesAutoConfiguration::class.java,
        DecisionServiceRegistryAutoConfiguration::class.java,
    ))

    // tag::cloudflare-configuration[]
    private fun configured() = runner.withPropertyValues(
        "embabel.agent.platform.models.cloudflare.enabled=true",
        "embabel.agent.platform.models.cloudflare.account-id=00000000000000000000000000000000",
        "embabel.agent.platform.models.cloudflare.model-names[0]=@cf/cloudflare/clef",
        "embabel.agent.platform.decisions.services.cloudflare-triage.provider=cloudflare",
        "embabel.agent.platform.decisions.services.cloudflare-triage.model=@cf/cloudflare/clef",
        "embabel.models.decision.default=cloudflare-triage",
        "embabel.models.decision.roles.support-triage=cloudflare-triage",
        "embabel.models.classification.default=cloudflare-triage",
        "embabel.models.classification.roles.ticket-routing=cloudflare-triage",
    )
    // end::cloudflare-configuration[]

    @Nested
    inner class Registration {
        @Test
        fun `starter presence creates no factory service or default`() {
            runner.run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(CloudflareModelFactory::class.java)
                assertThat(context).doesNotHaveBean(DecisionService::class.java)
                assertThat(context.getBean(DecisionServiceRegistry::class.java).registrationNames()).isEmpty()
            }
        }

        @Test
        fun `explicit properties register named default and both roles without a token`() {
            configured().run { context ->
                assertThat(context).hasNotFailed()
                val service = context.getBean("cloudflare-triage", DecisionService::class.java)
                val registry = context.getBean(DecisionServiceRegistry::class.java)
                assertThat(registry.registrationNames()).containsExactly("cloudflare-triage")
                assertThat(registry.decisions().defaultService()).isSameAs(service)
                assertThat(registry.decisions().byRole("support-triage")).isSameAs(service)
                assertThat(registry.classifications().defaultService()).isSameAs(service)
                assertThat(registry.classifications().byRole("ticket-routing")).isSameAs(service)
                assertThat(service.name).isEqualTo("@cf/cloudflare/clef")
                assertThat(service.provider).isEqualTo("Cloudflare Workers AI")
                assertThat(service.capabilities().questionKinds).containsExactly(QuestionKind.PROPOSITION, QuestionKind.CHOICE, QuestionKind.RATING)
                assertThat(context.getBean(CloudflareModelFactory::class.java).describe(service.name).capabilities).isEqualTo(service.capabilities())
            }
        }

        @Test
        fun `declared factory registers locally without evaluating its token`() {
            val tokens = AtomicInteger()
            val factory = CloudflareModelFactory(CloudflareClientOptions("00000000000000000000000000000000"),
                tokenSupplier = Supplier { tokens.incrementAndGet(); error("must stay deferred") })
            configured().withBean(CloudflareModelFactory::class.java, { factory }).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(DecisionServiceRegistry::class.java).decisions().byRole("support-triage").name).isEqualTo("@cf/cloudflare/clef")
                assertThat(tokens.get()).isZero()
            }
        }

        @Test
        fun `source tagged application factory needs no startup token`() {
            configured().withUserConfiguration(CloudflareApplicationConfiguration::class.java).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(DecisionServiceRegistry::class.java).decisions().defaultService().name).isEqualTo("@cf/cloudflare/clef")
            }
        }

        @Test
        fun `factory opt in alone adds no decision default or service`() {
            runner.withPropertyValues("embabel.agent.platform.models.cloudflare.enabled=true",
                "embabel.agent.platform.models.cloudflare.account-id=00000000000000000000000000000000").run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(CloudflareModelFactory::class.java)
                assertThat(context.getBean(DecisionServiceRegistry::class.java).registrationNames()).isEmpty()
            }
        }
    }

    @Nested
    inner class Coexistence {
        @Test
        fun `Cloudflare TypeSafe and prompted LLM providers share selectors without inference at startup`() {
            val tokens = AtomicInteger()
            val typeSafe = TypeSafeModelFactory(Supplier { tokens.incrementAndGet(); error("must stay deferred") })
            val operations = mockk<LlmOperations>()
            val chat = mockk<ChatModel>()
            val modelProvider = mockk<ModelProvider>()
            every { modelProvider.getLlm(any()) } returns SpringAiLlmService("review-model", "example", chat)
            val llm = LlmDecisionServiceFactory(operations, modelProvider, object : RetryProperties {
                override val maxAttempts = 1
                override val backoffMillis = 100L
                override val backoffMultiplier = 2.0
                override val backoffMaxInterval = 1000L
                override val propertyPrefix = "example"
            })
            configured().withBean(TypeSafeModelFactory::class.java, { typeSafe })
                .withBean(LlmDecisionServiceFactory::class.java, { llm }).withPropertyValues(
                    "embabel.agent.platform.decisions.services.jev.provider=typesafe",
                    "embabel.agent.platform.decisions.services.jev.model=jev-latest",
                    "embabel.agent.platform.decisions.llm.services.prompted-review.llm=review-model",
                    "embabel.models.decision.roles.legacy=jev",
                    "embabel.models.classification.roles.review=prompted-review",
                ).run { context ->
                    assertThat(context).hasNotFailed()
                    val registry = context.getBean(DecisionServiceRegistry::class.java)
                    assertThat(registry.registrationNames()).containsExactlyInAnyOrder("cloudflare-triage", "jev", "prompted-review")
                    assertThat(registry.decisions().defaultService().name).isEqualTo("@cf/cloudflare/clef")
                    assertThat(registry.decisions().byRole("legacy").provider).isEqualTo("TypeSafe")
                    assertThat(registry.classifications().byRole("review").name).isEqualTo("review-model")
                    assertThat(tokens.get()).isZero()
                    verify { operations wasNot Called; chat wasNot Called }
                }
        }

        @Test
        fun `application named service overrides generic registration and answers typed selection`() {
            // tag::cloudflare-application-override[]
            configured().withUserConfiguration(ApplicationServiceOverride::class.java).run { context ->
                assertThat(context).hasNotFailed()
                val registry = context.getBean(DecisionServiceRegistry::class.java)
                val service = context.getBean("cloudflare-triage", StubDecisionService::class.java)
                assertThat(service.calls()).isEmpty()
                val urgent = Questions.named("urgent").proposition("Is this urgent?").build()
                val response = registry.decisions().byRole("support-triage").ask("An outage", DecisionSpec.of(urgent))
                assertThat(response.answer(urgent)).isInstanceOf(PropositionResult.Answered::class.java)
                assertThat(service.calls()).containsExactly("askQuestionSet")
            }
            // end::cloudflare-application-override[]
        }

        @Test
        fun `same registration in generic and prompted configuration fails`() {
            configured().withPropertyValues("embabel.agent.platform.decisions.llm.services.cloudflare-triage.llm=review-model")
                .run { context -> assertThat(context).hasFailed() }
        }

        @Test
        fun `non service bean collision fails`() {
            configured().withBean("cloudflare-triage", String::class.java, { "occupied" })
                .run { context -> assertThat(context).hasFailed() }
        }

        @Test
        fun `unsupported configured model and missing role fail startup`() {
            configured().withPropertyValues("embabel.agent.platform.decisions.services.cloudflare-triage.model=@cf/cloudflare/clef-omni")
                .run { context -> assertThat(context).hasFailed() }
            configured().withPropertyValues("embabel.models.decision.roles.support-triage=missing")
                .run { context -> assertThat(context).hasFailed() }
        }

        @Test
        fun `typed selector rejects unsupported capability before invoking override`() {
            val service = StubDecisionService.builder("choice-only").capabilities(DecisionCapabilities.of(setOf(QuestionKind.CHOICE))).build()
            configured().withBean("cloudflare-triage", DecisionService::class.java, { service }).run { context ->
                assertThat(context).hasNotFailed()
                val urgent = Questions.named("urgent").proposition("Is this urgent?").build()
                assertThrows<UnsupportedDecisionException> {
                    context.getBean(DecisionServiceRegistry::class.java).decisions().defaultService().ask("input", DecisionSpec.of(urgent))
                }
                assertThat(service.calls()).isEmpty()
            }
        }
    }

    // tag::cloudflare-application-configuration[]
    @Configuration(proxyBeanMethods = false)
    class CloudflareApplicationConfiguration {
        @Bean
        fun cloudflareModelFactory() = CloudflareModelFactory(
            CloudflareClientOptions("00000000000000000000000000000000"),
            listOf("@cf/cloudflare/clef"),
        )
    }
    // end::cloudflare-application-configuration[]

    @Configuration(proxyBeanMethods = false)
    class ApplicationServiceOverride {
        @Bean("cloudflare-triage")
        fun triage() = StubDecisionService.builder("application-triage")
            .proposition("urgent", PropositionResult.Answered(true, ModelProvenance("example", "stub"))).build()
    }
}
