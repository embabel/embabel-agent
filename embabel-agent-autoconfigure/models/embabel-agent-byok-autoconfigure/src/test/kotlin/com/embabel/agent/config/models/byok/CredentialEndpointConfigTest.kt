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
package com.embabel.agent.config.models.byok

import com.embabel.agent.anthropic.AnthropicModelFactory
import com.embabel.agent.api.models.AnthropicModels
import com.embabel.agent.api.models.AtlasCloudModels
import com.embabel.agent.api.models.DeepSeekModels
import com.embabel.agent.api.models.GoogleGenAiModels
import com.embabel.agent.api.models.MistralAiModels
import com.embabel.agent.api.models.OpenAiModels
import com.embabel.agent.autoconfigure.models.byok.AgentByokAutoConfiguration
import com.embabel.agent.openai.OpenAiCompatibleModelFactory
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.model.ByRoleModelSelectionCriteria
import com.embabel.common.ai.model.ConfigurableModelProvider
import com.embabel.common.ai.model.ConfigurableModelProviderProperties
import com.embabel.common.ai.model.CredentialEndpoint
import com.embabel.common.ai.model.CredentialEndpointResolver
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelPlatform
import com.embabel.common.ai.model.ModelProvider.Companion.CHEAPEST_ROLE
import com.embabel.common.ai.model.ModelSelectionContext
import com.embabel.common.ai.model.ModelSelectionContextHolder
import com.embabel.common.ai.model.PricingModel
import com.embabel.common.ai.model.ProviderCredential
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.GenericBeanDefinition
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import com.embabel.chat.UserMessage
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Per-user keys do not work out of the box unless something ships a [ModelPlatform] that builds chat:
 * a role returning `RoleResolution.Credential` fails with `NoSuitableModelException` until the
 * application supplies one, and every application that has needed one has written the same `when`
 * over provider names. Putting `starter-byok` on the classpath should be enough, and these tests
 * pin that it is.
 *
 * Nothing here reaches the network. The shipped platforms build a chat client from a key without
 * validating it - validation is a separate `buildValidated` call neither bean makes - so the keys
 * below are arbitrary strings and the suite runs the same on a build agent as locally.
 */
class CredentialEndpointConfigTest {

    private companion object {

        /**
         * Never sent anywhere: the platforms under test build a client from a key rather than
         * calling the provider with it. Shaped like a real key only so a log line accidentally
         * carrying it would be recognisable as test data.
         */
        const val TEST_API_KEY = "sk-test-not-a-real-key"

        /**
         * Bean name of the shipped platform for OpenAI-compatible endpoints that have no named
         * platform: those a resolver returns, and Gemini and Atlas Cloud.
         */
        const val OPENAI_COMPATIBLE_PLATFORM_BEAN = CredentialEndpointConfig.OPENAI_COMPATIBLE_MODEL_PLATFORM

        /** Bean name of the shipped OpenAI platform, and the name an application overrides it by. */
        const val OPENAI_PLATFORM_BEAN = CredentialEndpointConfig.OPENAI_MODEL_PLATFORM

        const val DEEP_SEEK_PLATFORM_BEAN = CredentialEndpointConfig.DEEP_SEEK_MODEL_PLATFORM

        const val MISTRAL_AI_PLATFORM_BEAN = CredentialEndpointConfig.MISTRAL_AI_MODEL_PLATFORM

        /** Bean name of the shipped Anthropic platform. */
        const val ANTHROPIC_PLATFORM_BEAN = CredentialEndpointConfig.ANTHROPIC_MODEL_PLATFORM

        /** Name carried by the service an overriding application builds, to tell it apart. */
        const val OVERRIDE_SERVICE_NAME = "from-the-application"

        /** A provider no shipped platform handles: real, but not part of the BYOK surface. */
        const val UNHANDLED_PROVIDER = "Cohere"

        /** A provider this framework does not ship, reached through an application's own gateway. */
        const val GATEWAY_PROVIDER = "OurGateway"

        const val GATEWAY_URL = "https://gateway.example.com/v1"

        /** A gateway speaking Anthropic's protocol, so the other half of the routing is exercised. */
        const val ANTHROPIC_GATEWAY_PROVIDER = "OurAnthropicGateway"

        /** What a service built through the proxy below reports, so precedence is visible. */
        const val PROXIED_OPENAI_PROVIDER = "openai-via-our-proxy"
    }

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AgentByokAutoConfiguration::class.java))

    /**
     * Every [ModelPlatform] that builds chat the context contributed, in no particular order - the
     * model provider consults all of them and takes the first non-null answer, so order is not something
     * these tests should depend on.
     */
    private fun platformsIn(context: ApplicationContext): List<ModelPlatform> =
        context.chatModelPlatforms().values.toList()

    /**
     * Ask each platform in turn for a service, exactly as
     * [com.embabel.common.ai.model.ConfigurableModelProvider] does, and return the first answer.
     *
     * @return the service built for [provider] and [model], or null if no platform handled it
     */
    private fun build(
        platforms: List<ModelPlatform>,
        provider: String,
        model: String,
    ): LlmService<*>? = platforms.firstNotNullOfOrNull {
        it.model(ProviderCredential(provider, TEST_API_KEY), LlmService.KIND, model)
    }

    /** As above, for a test that needs the gateway to be a socket it controls. */
    private fun build(
        platforms: List<ModelPlatform>,
        provider: String,
        model: String,
        gatewayUrl: String,
    ): LlmService<*>? = OwnGatewayEndpoint.gatewayUrl.set(gatewayUrl).let { build(platforms, provider, model) }

    @Test
    fun `a platform per named provider, plus one for other OpenAI-compatible endpoints`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            // Every shipped platform builds chat, so all five are here.
            assertThat(context.chatModelPlatforms()).containsOnlyKeys(
                OPENAI_PLATFORM_BEAN,
                DEEP_SEEK_PLATFORM_BEAN,
                MISTRAL_AI_PLATFORM_BEAN,
                ANTHROPIC_PLATFORM_BEAN,
                OPENAI_COMPATIBLE_PLATFORM_BEAN,
            )
        }
    }

    @Test
    fun `every provider the BYOK surface supports builds with no application code`() {
        /*
         * The requirement, and the one test that pins its scope: not OpenAI and Anthropic, but
         * every provider a BYOK deployment can already validate a key against. `embabel-agent-openai`
         * speaks to five of these over one wire protocol, so shipping OpenAI alone would leave four
         * for every application to hand-write - which is the duplication this is meant to end.
         */
        val wholeSurface = mapOf(
            AnthropicModels.PROVIDER to AnthropicModels.CLAUDE_HAIKU_4_5,
            OpenAiModels.PROVIDER to OpenAiModels.GPT_41_MINI,
            DeepSeekModels.PROVIDER to DeepSeekModels.DEEPSEEK_V4_FLASH,
            MistralAiModels.PROVIDER to MistralAiModels.MINISTRAL_8B,
            GoogleGenAiModels.PROVIDER to GoogleGenAiModels.GEMINI_2_5_FLASH,
            AtlasCloudModels.PROVIDER to AtlasCloudModels.QWEN3_5_FLASH,
        )
        contextRunner.run { context ->
            val platforms = platformsIn(context)
            wholeSurface.forEach { (provider, model) ->
                val service = build(platforms, provider, model)

                assertThat(service?.provider).describedAs(provider).isEqualTo(provider)
                assertThat(service?.name).describedAs(provider).isEqualTo(model)
            }
        }
    }

    @Test
    fun `a user key builds an Anthropic service for the model the role named`() {
        contextRunner.run { context ->
            val service = build(platformsIn(context), AnthropicModels.PROVIDER, AnthropicModels.CLAUDE_HAIKU_4_5)

            assertThat(service?.name).isEqualTo(AnthropicModels.CLAUDE_HAIKU_4_5)
            assertThat(service?.provider).isEqualTo(AnthropicModels.PROVIDER)
        }
    }

    @Test
    fun `a user key builds an OpenAI service for the model the role named`() {
        contextRunner.run { context ->
            val service = build(platformsIn(context), OpenAiModels.PROVIDER, OpenAiModels.GPT_41_MINI)

            assertThat(service?.name).isEqualTo(OpenAiModels.GPT_41_MINI)
            assertThat(service?.provider).isEqualTo(OpenAiModels.PROVIDER)
        }
    }

    @Test
    fun `a model name this framework version has never heard of still builds`() {
        /*
         * The model name comes from configuration, and a provider ships new ones between framework
         * releases. Rejecting an unknown name would make a BYOK deployment unable to use a model
         * released after the version it runs - so the platform passes the name through, and a name
         * the provider does not offer fails at the provider rather than here.
         */
        contextRunner.run { context ->
            val service = build(platformsIn(context), OpenAiModels.PROVIDER, "gpt-released-next-year")

            assertThat(service?.name).isEqualTo("gpt-released-next-year")
        }
    }

    @Test
    fun `provider names are matched case-insensitively`() {
        /*
         * Keys arrive from user input as often as from yaml, so "openai" and "OpenAI" are the same
         * provider. Getting this wrong is one of the ways the hand-written copies of this `when`
         * fail, and it fails at runtime rather than at compile time.
         */
        contextRunner.run { context ->
            val platforms = platformsIn(context)

            assertThat(build(platforms, "openai", OpenAiModels.GPT_41_MINI)).isNotNull()
            assertThat(build(platforms, "OPENAI", OpenAiModels.GPT_41_MINI)).isNotNull()
            assertThat(build(platforms, "anthropic", AnthropicModels.CLAUDE_HAIKU_4_5)).isNotNull()
            assertThat(build(platforms, "ANTHROPIC", AnthropicModels.CLAUDE_HAIKU_4_5)).isNotNull()
            assertThat(build(platforms, "mistral ai", MistralAiModels.MINISTRAL_8B)).isNotNull()
            assertThat(build(platforms, "deepseek", DeepSeekModels.DEEPSEEK_V4_FLASH)).isNotNull()
        }
    }

    @Test
    fun `the built service reports the framework's spelling of the provider, not the caller's`() {
        // A credential carries whatever the application stored. If that spelling reached the built
        // service, cost and metadata lookups would key on two names for one provider.
        contextRunner.run { context ->
            val service = build(platformsIn(context), "MISTRAL AI", MistralAiModels.MINISTRAL_8B)

            assertThat(service?.provider).isEqualTo(MistralAiModels.PROVIDER)
        }
    }

    @Test
    fun `each platform declines the other's provider rather than answering for it`() {
        /*
         * Asserted per-platform, not just on the pair. `build` takes the first non-null answer, so a
         * platform that wrongly answered for everything would still look correct there as long as
         * the right one happened to be consulted first - which is bean ordering, not behaviour.
         */
        contextRunner.run { context ->
            val beans = context.chatModelPlatforms()
            val openAi = beans.getValue(OPENAI_PLATFORM_BEAN)
            val anthropic = beans.getValue(ANTHROPIC_PLATFORM_BEAN)
            val anthropicKey = ProviderCredential(AnthropicModels.PROVIDER, TEST_API_KEY)
            val openAiKey = ProviderCredential(OpenAiModels.PROVIDER, TEST_API_KEY)

            assertThat(openAi.model(anthropicKey, LlmService.KIND, AnthropicModels.CLAUDE_HAIKU_4_5)).isNull()
            assertThat(anthropic.model(openAiKey, LlmService.KIND, OpenAiModels.GPT_41_MINI)).isNull()
        }
    }

    @Test
    fun `a provider neither platform handles returns null rather than a wrong service`() {
        // Returning null is how a platform declines: the model provider tries the next one, and warns
        // naming the provider if none answer. Answering anyway would build an OpenAI client
        // pointed at someone else's key.
        contextRunner.run { context ->
            assertThat(build(platformsIn(context), UNHANDLED_PROVIDER, "mistral-large")).isNull()
        }
    }

    @Test
    fun `an application platform wins over the shipped one, and only that one`() {
        // Matters for a custom base URL or a proxy, which is exactly when an application needs to
        // override rather than add. The Anthropic platform must survive the override - which is why
        // the shipped beans back off by name rather than by type.
        contextRunner.withUserConfiguration(OwnOpenAiPlatform::class.java).run { context ->
            val platforms = platformsIn(context)
            // The application's bean took the shipped name; no second OpenAI bean was added.
            assertThat(context.chatModelPlatforms()).hasSize(5).containsKey(OPENAI_PLATFORM_BEAN)
            // An OpenAI key now gets the application's model.
            assertThat(build(platforms, OpenAiModels.PROVIDER, "anything")?.name).isEqualTo(OVERRIDE_SERVICE_NAME)
            // The other providers still get their shipped platforms' models.
            assertThat(build(platforms, AnthropicModels.PROVIDER, AnthropicModels.CLAUDE_HAIKU_4_5)?.name)
                .isEqualTo(AnthropicModels.CLAUDE_HAIKU_4_5)
            assertThat(build(platforms, DeepSeekModels.PROVIDER, DeepSeekModels.DEEPSEEK_V4_FLASH)?.name)
                .isEqualTo(DeepSeekModels.DEEPSEEK_V4_FLASH)
            assertThat(build(platforms, MistralAiModels.PROVIDER, MistralAiModels.MINISTRAL_8B)?.name)
                .isEqualTo(MistralAiModels.MINISTRAL_8B)
        }
    }

    @Test
    fun `a provider module absent from the classpath contributes no platform`() {
        // The gate that lets this module stay usable without both provider modules on the
        // classpath, since they are optional dependencies.
        contextRunner
            .withClassLoader(FilteredClassLoader(AnthropicModelFactory::class.java))
            .run { context ->
                assertThat(context).hasNotFailed()
                // Only the platforms from embabel-agent-openai are left.
                assertThat(context.chatModelPlatforms()).containsOnlyKeys(
                    OPENAI_PLATFORM_BEAN,
                    DEEP_SEEK_PLATFORM_BEAN,
                    MISTRAL_AI_PLATFORM_BEAN,
                    OPENAI_COMPATIBLE_PLATFORM_BEAN,
                )
            }
    }

    @Test
    fun `neither provider module present leaves the placeholder working`() {
        // A BYOK deployment that brings its own provider wiring still has to start.
        contextRunner
            .withClassLoader(
                FilteredClassLoader(
                    AnthropicModelFactory::class.java,
                    OpenAiCompatibleModelFactory::class.java,
                ),
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.chatModelPlatforms()).isEmpty()
                assertThat(context).hasBean(SetupRequiredLlm.NAME)
            }
    }

    @Test
    fun `the configuration loads when the OpenAI module is really absent from the classpath`() {
        contextWithout("com.embabel.agent.openai.", "org.springframework.ai.openai.").use { context ->
            assertThat(context.chatModelPlatforms()).containsOnlyKeys(ANTHROPIC_PLATFORM_BEAN)
            assertThat(context.embeddingModelPlatforms()).isEmpty()
        }
    }

    @Test
    fun `the configuration loads when the Anthropic module is really absent from the classpath`() {
        contextWithout("com.embabel.agent.anthropic.", "org.springframework.ai.anthropic.", "com.anthropic.").use { context ->
            assertThat(context.chatModelPlatforms()).containsOnlyKeys(
                OPENAI_PLATFORM_BEAN,
                DEEP_SEEK_PLATFORM_BEAN,
                MISTRAL_AI_PLATFORM_BEAN,
                OPENAI_COMPATIBLE_PLATFORM_BEAN,
            )
            // DeepSeek has no embeddings API.
            assertThat(context.embeddingModelPlatforms()).containsOnlyKeys(
                OPENAI_PLATFORM_BEAN,
                MISTRAL_AI_PLATFORM_BEAN,
                OPENAI_COMPATIBLE_PLATFORM_BEAN,
            )
        }
    }

    @Test
    fun `the OpenAI platform builds a service when the Anthropic module is really absent from the classpath`() {
        contextWithout("com.embabel.agent.anthropic.", "org.springframework.ai.anthropic.", "com.anthropic.").use { context ->
            assertThat(build(platformsIn(context), OpenAiModels.PROVIDER, OpenAiModels.GPT_41_MINI)?.name)
                .isEqualTo(OpenAiModels.GPT_41_MINI)
        }
    }

    /**
     * A started context whose BYOK configuration classes are loaded by a
     * [WithoutPackagesClassLoader], so every class they name resolves through it and the given
     * packages are missing.
     *
     * The auto-configuration is registered by class name, as Spring Boot imports it, so Spring
     * reads its annotations from bytecode. [ApplicationContextRunner] cannot be used: it loads
     * configuration classes through the test's class loader.
     */
    private fun contextWithout(vararg absentPackages: String): AnnotationConfigApplicationContext {
        val classLoader = WithoutPackagesClassLoader(javaClass.classLoader, absentPackages.toList())
        return AnnotationConfigApplicationContext().apply {
            setClassLoader(classLoader)
            beanFactory.beanClassLoader = classLoader
            registerBeanDefinition(
                AgentByokAutoConfiguration::class.java.name,
                GenericBeanDefinition().apply { beanClassName = AgentByokAutoConfiguration::class.java.name },
            )
            refresh()
        }
    }

    @Test
    fun `the model provider resolves a role through a user's key using the shipped platform`() {
        /*
         * The end-to-end case the issue is actually about, and the only test here that exercises
         * the wiring rather than the beans: a pure BYOK deployment, a role configured per provider,
         * a key on the call context - and a real service, with no application code registering
         * anything. Every other test in this class would still pass if the model provider never consulted
         * these beans at all.
         */
        contextRunner.run { context ->
            val modelProvider = pureByokProviderUsing(platformsIn(context))

            val llm = ModelSelectionContextHolder.with(contextWithOpenAiKey()) {
                modelProvider.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE))
            }

            assertThat(llm.name).isEqualTo(OpenAiModels.GPT_41_MINI)
            assertThat(llm.provider).isEqualTo(OpenAiModels.PROVIDER)
        }
    }

    @Test
    fun `the model provider caches the built service, so the model platform need not`() {
        /*
         * Pins the reason these beans hold no cache of their own. The model provider already
         * caches per (provider, key, model) behind a bounded LRU; a cache in the bean would be a
         * second, unbounded one holding a service per key the deployment has ever seen. If the
         * model provider ever stopped caching, this fails and the decision gets revisited - rather than
         * every BYOK deployment quietly rebuilding a client on every call.
         */
        val builds = AtomicInteger()
        val counting = ModelPlatform.builder()
            .builds(LlmService.KIND) { credential, model ->
                if (!credential.provider.equals(OpenAiModels.PROVIDER, ignoreCase = true)) null
                else {
                    builds.incrementAndGet()
                    SpringAiLlmService(name = model, provider = OpenAiModels.PROVIDER, chatModel = SetupRequiredChatModel())
                }
            }
            .build()
        val modelProvider = pureByokProviderUsing(listOf(counting))

        repeat(3) {
            ModelSelectionContextHolder.with(contextWithOpenAiKey()) {
                modelProvider.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE))
            }
        }

        assertThat(builds.get()).isEqualTo(1)
    }

    /**
     * A pure BYOK deployment: the placeholder is the only registered model, and `cheapest` is
     * configured per provider so a user's key selects a model without the deployment holding one.
     */
    private fun pureByokProviderUsing(platforms: List<ModelPlatform>) =
        ConfigurableModelProvider(
            llms = listOf(SetupRequiredLlm.llmService()),
            embeddingServices = emptyList(),
            properties = ConfigurableModelProviderProperties(
                defaultLlm = SetupRequiredLlm.NAME,
                roles = mapOf(
                    CHEAPEST_ROLE to mapOf(
                        OpenAiModels.PROVIDER to LlmOptions.withModel(OpenAiModels.GPT_41_MINI),
                    ),
                ),
            ),
            modelPlatforms = platforms,
        )

    /** The per-call context an application sets at its request boundary once a user's key is known. */
    private fun contextWithOpenAiKey() =
        ModelSelectionContext(credential = ProviderCredential(OpenAiModels.PROVIDER, TEST_API_KEY))

    /**
     * An application's own OpenAI platform, registered under the bean name the shipped one uses so
     * that it replaces the shipped one.
     */
    @Configuration(proxyBeanMethods = false)
    class OwnOpenAiPlatform {

        @Bean(OPENAI_PLATFORM_BEAN)
        fun openAiModelPlatform(): ModelPlatform = applicationChatPlatform(OVERRIDE_SERVICE_NAME, OpenAiModels.PROVIDER)
    }

    /**
     * The tier an application is meant to reach for: state where a key goes, and let the platform
     * build the client. Nothing here names a type under `com.embabel.agent.spi`, which is the whole
     * point - the documentation asks application code not to depend on that package, and the only
     * other extension point does.
     */
    @Nested
    inner class Endpoints {

        @Test
        fun `an application adds a provider the framework does not ship`() {
            contextRunner.withUserConfiguration(OwnGatewayEndpoint::class.java).run { context ->
                val service = build(platformsIn(context), GATEWAY_PROVIDER, "some-model")

                assertThat(service?.provider).isEqualTo(GATEWAY_PROVIDER)
                assertThat(service?.name).isEqualTo("some-model")
            }
        }

        @Test
        fun `an application resolver takes precedence over the shipped endpoint for the same provider`() {
            /*
             * Overriding a shipped provider - for a proxy, or a custom base URL - is answering for
             * it, with no bean name to match and no `@Order` to get right. The resolver below is
             * deliberately at LOWEST_PRECEDENCE, the worst case: had the shipped endpoints been
             * resolver beans they would have tied with it, and the winner would have come down to
             * bean registration order.
             */
            contextRunner.withUserConfiguration(OwnOpenAiEndpoint::class.java).run { context ->
                val service = build(platformsIn(context), OpenAiModels.PROVIDER, OpenAiModels.GPT_41_MINI)

                assertThat(service?.provider).isEqualTo(PROXIED_OPENAI_PROVIDER)
            }
        }

        @Test
        fun `the named OpenAI platform returns null for a key an application resolver answers for`() {
            // The test above takes the first answer in bean order. This one asks each bean, so the
            // resolver's endpoint wins whatever order the beans are in.
            contextRunner.withUserConfiguration(OwnOpenAiEndpoint::class.java).run { context ->
                val beans = context.chatModelPlatforms()
                val openAiKey = ProviderCredential(OpenAiModels.PROVIDER, TEST_API_KEY)

                // The named platform returns null, because a resolver answered for the key.
                assertThat(beans.getValue(OPENAI_PLATFORM_BEAN).model(openAiKey, LlmService.KIND, OpenAiModels.GPT_41_MINI))
                    .isNull()
                // The platform for resolver endpoints builds it at the proxy.
                assertThat(beans.getValue(OPENAI_COMPATIBLE_PLATFORM_BEAN).model(openAiKey, LlmService.KIND, OpenAiModels.GPT_41_MINI)?.provider)
                    .isEqualTo(PROXIED_OPENAI_PROVIDER)
            }
        }

        @Test
        fun `nothing shipped competes as a resolver bean`() {
            // The other half of the test above, and the reason it holds whatever an application does:
            // what this module knows about a provider is a fallback, not a bean in the same stream.
            contextRunner.run { context ->
                assertThat(context.getBeansOfType(CredentialEndpointResolver::class.java)).isEmpty()
            }
            contextRunner.withUserConfiguration(OwnGatewayEndpoint::class.java).run { context ->
                assertThat(context.getBeansOfType(CredentialEndpointResolver::class.java)).hasSize(1)
            }
        }

        @Test
        fun `the key goes to the base URL the resolver named`() {
            /*
             * The one property a gateway deployment actually depends on, and the one no assertion
             * on the built service can see: provider and model come from elsewhere, so dropping
             * `baseUrl` on the way to the client would leave every other test here green and send
             * every user's key to OpenAI. Asserted against a real socket for that reason.
             */
            val server = HttpServer.create(InetSocketAddress(0), 0)
            val hit = CompletableFuture<String>()
            try {
                server.createContext("/") { exchange ->
                    hit.complete(exchange.requestURI.path)
                    exchange.requestBody.use { it.readBytes() }
                    val body = "{}".toByteArray()
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                server.start()
                val gateway = "http://localhost:${server.address.port}"

                contextRunner.withUserConfiguration(OwnGatewayEndpoint::class.java).run { context ->
                    val service = build(platformsIn(context), GATEWAY_PROVIDER, "some-model", gateway)
                    // The response above is not a chat completion, so the call fails - after the
                    // request has arrived, which is the whole question.
                    runCatching {
                        service?.createMessageSender(LlmOptions())?.call(listOf(UserMessage("Hi")), emptyList())
                    }

                    assertThat(hit.getNow(null)).describedAs("request never reached the gateway").isNotNull()
                }
            } finally {
                server.stop(0)
            }
        }

        @Test
        fun `an application endpoint on Anthropic's protocol carries its own metadata`() {
            // The Anthropic half of the resolver path: everything the endpoint states has to reach
            // the built service, or a gateway fronting Anthropic reports itself as Anthropic.
            contextRunner.withUserConfiguration(OwnAnthropicEndpoint::class.java).run { context ->
                val service = build(platformsIn(context), ANTHROPIC_GATEWAY_PROVIDER, "some-model")

                assertThat(service?.provider).isEqualTo(ANTHROPIC_GATEWAY_PROVIDER)
                assertThat(service?.pricingModel).isEqualTo(PricingModel.ALL_YOU_CAN_EAT)
                assertThat(service?.knowledgeCutoffDate).isEqualTo(LocalDate.of(2026, 1, 31))
            }
        }

        @Test
        fun `each platform builds only for its own protocol`() {
            /*
             * The routing this split turns on. A resolver names a wire protocol, and the platform for
             * the other protocol must decline rather than point a client at an endpoint it cannot
             * talk to - which, taking the first non-null answer, would otherwise depend on bean order.
             */
            contextRunner.withUserConfiguration(OwnGatewayEndpoint::class.java).run { context ->
                val beans = context.chatModelPlatforms()
                val gatewayKey = ProviderCredential(GATEWAY_PROVIDER, TEST_API_KEY)

                assertThat(beans.getValue(ANTHROPIC_PLATFORM_BEAN).model(gatewayKey, LlmService.KIND, "some-model")).isNull()
                assertThat(beans.getValue(OPENAI_COMPATIBLE_PLATFORM_BEAN).model(gatewayKey, LlmService.KIND, "some-model"))
                    .isNotNull()
            }
        }

        @Test
        fun `the OpenAI-compatible platform returns null for a key a resolver sends to Anthropic's protocol`() {
            // The other direction of the test above: the resolver returns an Anthropic endpoint,
            // so only the Anthropic platform builds the key.
            contextRunner.withUserConfiguration(OwnAnthropicEndpoint::class.java).run { context ->
                val beans = context.chatModelPlatforms()
                val gatewayKey = ProviderCredential(ANTHROPIC_GATEWAY_PROVIDER, TEST_API_KEY)

                assertThat(beans.getValue(OPENAI_COMPATIBLE_PLATFORM_BEAN).model(gatewayKey, LlmService.KIND, "some-model")).isNull()
                assertThat(beans.getValue(ANTHROPIC_PLATFORM_BEAN).model(gatewayKey, LlmService.KIND, "some-model")?.provider)
                    .isEqualTo(ANTHROPIC_GATEWAY_PROVIDER)
            }
        }

        @Test
        fun `a BYOK call is not charged to the deployment`() {
            // The user's own key is billed, so counting the call against the deployment's cost
            // accounting would be wrong in the one direction that matters.
            contextRunner.run { context ->
                val platforms = platformsIn(context)

                assertThat(build(platforms, OpenAiModels.PROVIDER, OpenAiModels.GPT_41_MINI)?.pricingModel)
                    .isEqualTo(PricingModel.ALL_YOU_CAN_EAT)
                assertThat(build(platforms, AnthropicModels.PROVIDER, AnthropicModels.CLAUDE_HAIKU_4_5)?.pricingModel)
                    .isEqualTo(PricingModel.ALL_YOU_CAN_EAT)
            }
        }

        @Test
        fun `a resolver declining every provider leaves the shipped ones answering`() {
            contextRunner.withUserConfiguration(OwnGatewayEndpoint::class.java).run { context ->
                val service = build(platformsIn(context), AnthropicModels.PROVIDER, AnthropicModels.CLAUDE_HAIKU_4_5)

                assertThat(service?.provider).isEqualTo(AnthropicModels.PROVIDER)
            }
        }
    }

    /**
     * A user's key reaches the same endpoint the deployment's would, so it gets the same timeouts:
     * nothing about a slow model changes because a different key pays for the call.
     */
    @Nested
    inner class Timeouts {

        @Test
        fun `a user key's chat call gives up after the provider's configured read timeout`() {
            withHangingGateway { gateway, requests ->
                contextRunner
                    .withUserConfiguration(OwnGatewayEndpoint::class.java)
                    .withPropertyValues("embabel.agent.platform.models.ourgateway.read-timeout=300ms")
                    .run { context ->
                        val service = build(platformsIn(context), GATEWAY_PROVIDER, "slow-model", gateway)

                        assertFailsWithinTenSeconds {
                            service?.createMessageSender(LlmOptions())?.call(listOf(UserMessage("Hi")), emptyList())
                        }
                        assertThat(requests.get()).describedAs("timed-out attempts").isEqualTo(3)
                    }
            }
        }

        @Test
        fun `a user key's embedding gives up after the provider's configured read timeout`() {
            withHangingGateway { gateway, requests ->
                OwnGatewayEndpoint.gatewayUrl.set(gateway)
                contextRunner
                    .withUserConfiguration(OwnGatewayEndpoint::class.java)
                    .withPropertyValues("embabel.agent.platform.models.ourgateway.read-timeout=300ms")
                    .run { context ->
                        // The gateway is resolver-routed, so the platform for other OpenAI-compatible endpoints builds it.
                        val platform = context.embeddingModelPlatforms().getValue(OPENAI_COMPATIBLE_PLATFORM_BEAN)

                        assertFailsWithinTenSeconds {
                            platform.model(ProviderCredential(GATEWAY_PROVIDER, TEST_API_KEY), EmbeddingService.KIND, "slow-embedding")
                        }
                        assertThat(requests.get()).describedAs("timed-out attempts").isEqualTo(3)
                    }
            }
        }

        @Test
        fun `a malformed timeout fails the call with an exception naming the property`() {
            contextRunner
                .withUserConfiguration(OwnGatewayEndpoint::class.java)
                .withPropertyValues("embabel.agent.platform.models.ourgateway.read-timeout=five minutes")
                .run { context ->
                    assertThat(context).hasNotFailed()
                    repeat(2) {
                        val failure = runCatching { build(platformsIn(context), GATEWAY_PROVIDER, "any-model") }
                            .exceptionOrNull()
                        assertThat(failure).isInstanceOf(InvalidProviderTimeoutException::class.java)
                        assertThat((failure as InvalidProviderTimeoutException).property)
                            .isEqualTo("embabel.agent.platform.models.ourgateway.read-timeout")
                    }
                }
        }

        @Test
        fun `a named platform reads the timeouts configured for its provider`() {
            // DeepSeek keys are built by deepSeekModelPlatform, not by a resolver, so this checks
            // that the named platform's path reads the provider's timeout properties.
            contextRunner
                .withPropertyValues("embabel.agent.platform.models.deepseek.read-timeout=five minutes")
                .run { context ->
                    val failure = runCatching {
                        context.chatModelPlatforms().getValue(DEEP_SEEK_PLATFORM_BEAN)
                            .model(ProviderCredential(DeepSeekModels.PROVIDER, TEST_API_KEY), LlmService.KIND, DeepSeekModels.DEEPSEEK_V4_FLASH)
                    }.exceptionOrNull()
                    // The malformed value was read, so the property name is in the exception.
                    assertThat(failure).isInstanceOf(InvalidProviderTimeoutException::class.java)
                    assertThat((failure as InvalidProviderTimeoutException).property)
                        .isEqualTo("embabel.agent.platform.models.deepseek.read-timeout")
                }
        }

        @Test
        fun `a built-in provider reads the prefix its own module configures`() {
            assertThat(CredentialEndpointConfig.timeoutPrefixFor(OpenAiModels.PROVIDER))
                .isEqualTo("embabel.agent.platform.models.openai")
            assertThat(CredentialEndpointConfig.timeoutPrefixFor(GoogleGenAiModels.PROVIDER))
                .isEqualTo("embabel.agent.platform.models.gemini")
            assertThat(CredentialEndpointConfig.timeoutPrefixFor(MistralAiModels.PROVIDER))
                .isEqualTo("embabel.agent.platform.models.mistralai")
        }

        private fun withHangingGateway(test: (String, AtomicInteger) -> Unit) {
            val server = HttpServer.create(InetSocketAddress(0), 0)
            val release = CountDownLatch(1)
            val requests = AtomicInteger()
            server.executor = Executors.newCachedThreadPool()
            server.createContext("/") { exchange ->
                requests.incrementAndGet()
                exchange.requestBody.use { it.readBytes() }
                release.await(60, TimeUnit.SECONDS)
                exchange.close()
            }
            server.start()
            try {
                test("http://localhost:${server.address.port}", requests)
            } finally {
                release.countDown()
                server.stop(0)
                OwnGatewayEndpoint.gatewayUrl.set(GATEWAY_URL)
            }
        }

        private fun assertFailsWithinTenSeconds(call: () -> Unit) {
            val started = System.nanoTime()
            assertThat(runCatching(call).isFailure).describedAs("call should fail").isTrue()
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10))
        }
    }

    /** An application bringing a provider of its own: one value, no SPI import. */
    @Configuration(proxyBeanMethods = false)
    class OwnGatewayEndpoint {

        companion object {
            /** Overridden by the test that needs the gateway to be a socket it can watch. */
            val gatewayUrl = AtomicReference(GATEWAY_URL)
        }

        @Bean
        fun ourGatewayEndpoint() = CredentialEndpointResolver { credential, _ ->
            if (!credential.provider.equals(GATEWAY_PROVIDER, ignoreCase = true)) null
            else CredentialEndpoint.OpenAiCompatible(provider = GATEWAY_PROVIDER, baseUrl = gatewayUrl.get())
        }
    }

    /** The same for a gateway speaking Anthropic's protocol rather than OpenAI's. */
    @Configuration(proxyBeanMethods = false)
    class OwnAnthropicEndpoint {

        @Bean
        fun ourAnthropicGatewayEndpoint() = CredentialEndpointResolver { credential, _ ->
            if (!credential.provider.equals(ANTHROPIC_GATEWAY_PROVIDER, ignoreCase = true)) null
            else CredentialEndpoint.Anthropic(
                provider = ANTHROPIC_GATEWAY_PROVIDER,
                baseUrl = GATEWAY_URL,
                knowledgeCutoffDate = LocalDate.of(2026, 1, 31),
            )
        }
    }

    /** An application routing a provider the framework already ships through its own proxy. */
    @Configuration(proxyBeanMethods = false)
    class OwnOpenAiEndpoint {

        @Bean
        @Order(Ordered.LOWEST_PRECEDENCE)
        fun proxiedOpenAiEndpoint() = CredentialEndpointResolver { credential, _ ->
            if (!credential.provider.equals(OpenAiModels.PROVIDER, ignoreCase = true)) null
            else CredentialEndpoint.OpenAiCompatible(provider = PROXIED_OPENAI_PROVIDER, baseUrl = GATEWAY_URL)
        }
    }
}

/**
 * Loads the BYOK configuration classes itself and refuses every class in [absentPackages], so a
 * test sees what an application without an optional provider module sees.
 *
 * [FilteredClassLoader] cannot show this. The configuration class is already loaded by the test's
 * class loader, which has both provider modules, so the types named in its method signatures
 * resolve there whatever the filter says.
 */
private class WithoutPackagesClassLoader(
    parent: ClassLoader,
    private val absentPackages: List<String>,
) : ClassLoader(parent) {

    private val ownPackages = listOf(
        "com.embabel.agent.config.models.byok.",
        "com.embabel.agent.autoconfigure.models.byok.",
    )

    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
        when {
            absentPackages.any(name::startsWith) -> throw ClassNotFoundException(name)
            ownPackages.none(name::startsWith) -> super.loadClass(name, resolve)
            else -> findLoadedClass(name) ?: defineOwn(name)
        }
    }

    private fun defineOwn(name: String): Class<*> {
        val bytes = parent.getResourceAsStream(name.replace('.', '/') + ".class")?.use { it.readBytes() }
            ?: throw ClassNotFoundException(name)
        return defineClass(name, bytes, 0, bytes.size)
    }
}
