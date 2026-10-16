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
package com.embabel.agent.autoconfigure.models.cloudflare

import com.embabel.agent.autoconfigure.models.typesafe.AgentTypeSafeAutoConfiguration
import com.embabel.agent.autoconfigure.platform.DecisionServiceRegistryAutoConfiguration
import com.embabel.agent.cloudflare.CloudflareModelFactory
import com.embabel.agent.config.models.cloudflare.CloudflareModelDefinition
import com.embabel.agent.config.models.cloudflare.CloudflareModelsRegistrar
import com.embabel.agent.config.models.cloudflare.CloudflareProperties
import com.embabel.common.ai.autoconfig.ProviderInitialization
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.decision.DecisionService
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.decision.support.StubDecisionService
import com.embabel.common.ai.model.DecisionServiceRegistry
import com.embabel.common.ai.model.ServiceSelectionException
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.MapPropertySource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class AgentCloudflareAutoConfigurationTest {

    private val account = "0123456789abcdef0123456789abcdef"
    private val prefix = CloudflareProperties.PREFIX
    private val request = PropositionRequest("A parcel came back unopened.", "The parcel needs attention")
    private val response =
        """{"success":true,"errors":[],"messages":[],"result":{"model":"clef","answers":{"q1":{"type":"noul","noul":0.8}}}}"""

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AgentCloudflareAutoConfiguration::class.java))
        .withPropertyValues("CLOUDFLARE_API_TOKEN=", "CLOUDFLARE_ACCOUNT_ID=")

    private fun configured() = runner.withPropertyValues("$prefix.api-key=cf-token", "$prefix.account-id=$account")

    @Nested
    inner class Registration {

        @Test
        fun `registers every decision model under its catalog name`() {
            configured().run { context ->
                assertTrue(context.startupFailure == null, context.startupFailure?.toString())
                assertEquals(setOf("clef", "clef-flash", "clef-omni", "cloudflare-jev"), context.getBeansOfType(DecisionService::class.java).keys)
                assertEquals(CloudflareModelFactory.CLEF, context.getBean("clef", DecisionService::class.java).name)
                assertEquals(CloudflareModelFactory.CLEF_FLASH, context.getBean("clef-flash", DecisionService::class.java).name)
                assertEquals(CloudflareModelFactory.CLEF_OMNI, context.getBean("clef-omni", DecisionService::class.java).name)
                assertEquals(CloudflareModelFactory.JEV, context.getBean("cloudflare-jev", DecisionService::class.java).name)
                assertEquals(CloudflareModelFactory.PROVIDER, context.getBean("clef", DecisionService::class.java).provider)
            }
        }

        @Test
        fun `catalog models inject by qualifier`() {
            configured().withUserConfiguration(FlashConsumer::class.java).run { context ->
                assertEquals(CloudflareModelFactory.CLEF_FLASH, context.getBean(FlashConsumer::class.java).flash.name)
            }
        }

        @Test
        fun `offers clef as the default candidate and reports what it registered`() {
            configured().run { context ->
                assertEquals(
                    DecisionServiceRegistry.DefaultCandidate("clef"),
                    context.getBean(DecisionServiceRegistry.DefaultCandidate::class.java),
                )
                val initialization = context.getBean(ProviderInitialization::class.java)
                assertEquals(CloudflareModelFactory.PROVIDER, initialization.provider)
                assertEquals(listOf("clef", "clef-flash", "clef-omni", "cloudflare-jev"), initialization.registeredDecisionModels.map { it.beanName })
            }
        }

        @Test
        fun `an application bean named clef replaces the catalog model and the candidate`() {
            val application = mockk<DecisionService>()
            configured().withBean("clef", DecisionService::class.java, { application }).run { context ->
                assertSame(application, context.getBean("clef"))
                assertTrue(context.getBeansOfType(DecisionServiceRegistry.DefaultCandidate::class.java).isEmpty())
            }
        }

        @Test
        fun `an application alias replaces clef and resolves defaults and roles`() {
            configured()
                .withConfiguration(AutoConfigurations.of(DecisionServiceRegistryAutoConfiguration::class.java))
                .withUserConfiguration(AliasedDecisionService::class.java)
                .withPropertyValues(
                    "embabel.models.decision.default=clef",
                    "embabel.models.decision.roles.review=clef",
                    "embabel.models.classification.default=clef",
                    "embabel.models.classification.roles.routing=clef",
                )
                .run { context ->
                    assertTrue(context.startupFailure == null, context.startupFailure?.toString())
                    val replacement = context.getBean("customDecision", DecisionService::class.java)
                    assertSame(replacement, context.getBean("clef"))
                    assertTrue(context.getBeansOfType(DecisionServiceRegistry.DefaultCandidate::class.java).isEmpty())
                    val registry = context.getBean(DecisionServiceRegistry::class.java)
                    assertEquals(setOf("customDecision", "clef-flash", "clef-omni", "cloudflare-jev"), registry.registrationNames().toSet())
                    assertSame(replacement, registry.decisions().defaultService())
                    assertSame(replacement, registry.decisions().byRole("review"))
                    assertSame(replacement, registry.classifications().defaultService())
                    assertSame(replacement, registry.classifications().byRole("routing"))
                    val initialization = context.getBean(ProviderInitialization::class.java)
                    assertFalse(initialization.registeredDecisionModels.any { it.beanName == "clef" })
                }
        }

        @Test
        fun `a catalog entry with a malformed model id fails startup with the format message`() {
            configured().withUserConfiguration(MalformedCatalogEntry::class.java).run { context ->
                val failure = context.startupFailure
                assertTrue(failure != null)
                val root = generateSequence(failure) { it.cause }.last()
                assertTrue(root is IllegalArgumentException, root.toString())
                assertEquals("A third-party Workers AI model id looks like <author>/<model>", root.message)
            }
        }

        @Test
        fun `an application factory keeps auto-configuration out and needs no token`() {
            runner.withBean(CloudflareModelFactory::class.java, { CloudflareModelFactory(account, { null }) }).run { context ->
                assertTrue(context.startupFailure == null)
                assertTrue(context.getBeansOfType(DecisionService::class.java).isEmpty())
            }
        }

        @Test
        fun `both providers require an explicit default for each family`() {
            val both = configured()
                .withConfiguration(
                    AutoConfigurations.of(
                        AgentTypeSafeAutoConfiguration::class.java,
                        DecisionServiceRegistryAutoConfiguration::class.java,
                    ),
                )
                .withPropertyValues("TYPESAFE_API_KEY=", "embabel.agent.platform.models.typesafe.api-key=ts-token")
            both.run { context ->
                assertTrue(context.startupFailure == null, context.startupFailure?.toString())
                val registry = context.getBean(DecisionServiceRegistry::class.java)
                val candidates = context.getBeansOfType(DecisionServiceRegistry.DefaultCandidate::class.java).values
                assertEquals(setOf("clef", "jev-latest"), candidates.map { it.name }.toSet())
                assertEquals(
                    ServiceSelectionException.Reason.AMBIGUOUS_DEFAULT,
                    assertThrows<ServiceSelectionException> { registry.decisions().defaultService() }.reason,
                )
                assertEquals(
                    ServiceSelectionException.Reason.AMBIGUOUS_DEFAULT,
                    assertThrows<ServiceSelectionException> { registry.classifications().defaultService() }.reason,
                )
            }
            both.withPropertyValues("embabel.models.decision.default=clef", "embabel.models.classification.default=jev-latest")
                .run { context ->
                    assertTrue(context.startupFailure == null, context.startupFailure?.toString())
                    val registry = context.getBean(DecisionServiceRegistry::class.java)
                    assertSame(context.getBean("clef"), registry.decisions().defaultService())
                    assertSame(context.getBean("jev-latest"), registry.classifications().defaultService())
                }
        }
    }

    @Nested
    inner class Credentials {

        @Test
        fun `the environment token wins and both sources are read on every call`() {
            val tokens = mutableMapOf<String, Any>("CLOUDFLARE_API_TOKEN" to "env-1", "$prefix.api-key" to "property-1")
            val builder = RestClient.builder()
            val server = MockRestServiceServer.bindTo(builder).build()
            listOf("env-1", "env-2", "property-1", "property-2").forEach { token ->
                server.expect(requestTo("https://api.cloudflare.com/client/v4/accounts/$account/ai/run/@cf/cloudflare/clef"))
                    .andExpect { assertEquals("Bearer $token", it.headers.getFirst(HttpHeaders.AUTHORIZATION)) }
                    .andRespond(withSuccess(response, MediaType.APPLICATION_JSON))
            }

            runner
                .withPropertyValues("$prefix.account-id=$account")
                .withInitializer { it.environment.propertySources.addFirst(MapPropertySource("rotating", tokens)) }
                .withBean("aiModelRestClientBuilder", RestClient.Builder::class.java, { builder })
                .run { context ->
                    val clef = context.getBean("clef", DecisionService::class.java)
                    assertTrue(clef.assess(request) is PropositionResult.Answered)
                    tokens["CLOUDFLARE_API_TOKEN"] = "env-2"
                    clef.assess(request)
                    tokens["CLOUDFLARE_API_TOKEN"] = ""
                    clef.assess(request)
                    tokens["$prefix.api-key"] = "property-2"
                    clef.assess(request)
                }
            server.verify()
        }

        @Test
        fun `the environment account id wins over the property`() {
            val other = "fedcba9876543210fedcba9876543210"
            val builder = RestClient.builder()
            val server = MockRestServiceServer.bindTo(builder).build()
            server.expect(requestTo("https://api.cloudflare.com/client/v4/accounts/$other/ai/run/@cf/cloudflare/clef"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON))

            configured()
                .withPropertyValues("CLOUDFLARE_ACCOUNT_ID=$other")
                .withBean("aiModelRestClientBuilder", RestClient.Builder::class.java, { builder })
                .run { context -> context.getBean("clef", DecisionService::class.java).assess(request) }
            server.verify()
        }

        @Test
        fun `startup fails without a token or an account, and never echoes them`() {
            assertStartupFails(runner.withPropertyValues("$prefix.account-id=$account"), "Cloudflare API key is required")
            assertStartupFails(runner.withPropertyValues("$prefix.api-key=cf-token"), "Cloudflare account id is required")
            assertStartupFails(
                runner.withPropertyValues("$prefix.api-key=cf-token", "$prefix.account-id=not-an-account"),
                "The Cloudflare account id must be 32 hexadecimal characters",
                "not-an-account",
            )
        }

        @Test
        fun `optional environment placeholders report missing credentials clearly`() {
            val placeholders = runner.withPropertyValues(
                "$prefix.api-key=\${CLOUDFLARE_API_TOKEN:}",
                "$prefix.account-id=\${CLOUDFLARE_ACCOUNT_ID:}",
            )
            assertStartupFails(placeholders, "Cloudflare account id is required")
            assertStartupFails(placeholders.withPropertyValues("$prefix.account-id=$account"), "Cloudflare API key is required")
        }

        /**
         * Starts the context, expects it to fail with a root cause message, and checks that no
         * message in the failure chain holds the token or an account id.
         *
         * @param runner the configured context runner
         * @param rootCause the expected root cause message
         * @param secrets further values the failure must not contain
         */
        private fun assertStartupFails(runner: ApplicationContextRunner, rootCause: String, vararg secrets: String) {
            runner.run { context ->
                val failure = context.startupFailure
                assertTrue(failure != null)
                val chain = generateSequence(failure) { it.cause }.toList()
                val messages = chain.joinToString { it.message.orEmpty() }
                assertEquals(rootCause, chain.last().message)
                (listOf("cf-token", account) + secrets).forEach { assertFalse(messages.contains(it), messages) }
            }
        }

        @Test
        fun `properties leave the token and account out of toString`() {
            val text = CloudflareProperties("cf-token", account, 1024).toString()
            assertFalse(text.contains("cf-token"))
            assertFalse(text.contains(account))
            assertTrue(text.contains("accountId=[CONFIGURED]"), text)
        }

        @Test
        fun `properties report an account id that isn't set as unset`() {
            listOf(null, " ").forEach { accountId ->
                val text = CloudflareProperties("cf-token", accountId, 1024).toString()
                assertTrue(text.contains("accountId=[UNSET]"), text)
            }
        }
    }

    @Nested
    inner class Limits {

        @Test
        fun `a response larger than max-response-bytes is an invalid response`() {
            val builder = RestClient.builder()
            val server = MockRestServiceServer.bindTo(builder).build()
            server.expect(requestTo("https://api.cloudflare.com/client/v4/accounts/$account/ai/run/@cf/cloudflare/clef"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON))
            val urgent = Questions.named("urgent").proposition("Is this urgent?").build()

            configured()
                .withPropertyValues("$prefix.max-response-bytes=64")
                .withBean("aiModelRestClientBuilder", RestClient.Builder::class.java, { builder })
                .run { context ->
                    val clef = context.getBean("clef", DecisionService::class.java)
                    assertTrue(response.length > 64)
                    assertEquals(FailureReason.INVALID_RESPONSE, clef.ask(request.input, DecisionSpec.of(urgent)).requestFailure)
                }
            server.verify()
        }
    }

    @Configuration(proxyBeanMethods = false)
    class AliasedDecisionService {

        @Bean("customDecision", "clef")
        fun customDecision(): DecisionService = StubDecisionService.builder("application-model").build()
    }

    @Configuration(proxyBeanMethods = false)
    class MalformedCatalogEntry {
        companion object {

            /**
             * Registers a Cloudflare catalog entry whose model id has the wrong case, the way the
             * starter registers its own catalog.
             *
             * @param beanFactory where the Cloudflare factory is read from
             * @return the registrar
             */
            @JvmStatic
            @Bean
            fun malformedCatalogRegistrar(beanFactory: BeanFactory): BeanDefinitionRegistryPostProcessor =
                CloudflareModelsRegistrar(listOf(CloudflareModelDefinition("typo", "TypeSafe/Jev")), beanFactory)
        }
    }

    @Configuration(proxyBeanMethods = false)
    class FlashConsumer {
        lateinit var flash: DecisionService

        @Bean
        fun flashUser(@Qualifier("clef-flash") flash: DecisionService): String {
            this.flash = flash
            return "flash"
        }
    }
}
