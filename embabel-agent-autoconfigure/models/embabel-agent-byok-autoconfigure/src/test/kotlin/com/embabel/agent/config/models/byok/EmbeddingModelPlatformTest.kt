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
import com.embabel.agent.autoconfigure.models.byok.AgentByokAutoConfiguration
import com.embabel.agent.openai.OpenAiCompatibleModelFactory
import com.embabel.common.ai.model.CredentialEndpoint
import com.embabel.common.ai.model.CredentialEndpointResolver
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.ModelPlatform
import com.embabel.common.ai.model.PricingModel
import com.embabel.common.ai.model.ProviderCredential
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

/**
 * The embedding half of what `embabel-agent-starter-byok` ships, which
 * [CredentialEndpointConfigTest] does not cover: that suite asks what a per-user key builds for
 * CHAT, and would stay green if the embedding platform were deleted.
 *
 * The distinction worth testing here is that the platform VALIDATES embedding models and not chat models. A
 * chat model that turns out to be wrong fails the call that used it; an embedding service states a
 * WIDTH, and that width becomes the shape of a vector index, so one built on an assumption is an
 * index that accepts writes no later model agrees with. So the width has to be OBSERVED, and the
 * only way to prove it was is to have the provider return an unusual one.
 *
 * Nothing here reaches the public internet: the probe is pointed at a socket this test opens.
 */
class EmbeddingModelPlatformTest {

    private companion object {

        /** Never sent anywhere real; shaped like a key only so a stray log line is recognisable. */
        const val TEST_API_KEY = "sk-test-not-a-real-key"

        /** Bean name of the shipped platform that builds embedding models, and the name an application overrides by. */
        const val EMBEDDING_PLATFORM_BEAN = CredentialEndpointConfig.OPENAI_COMPATIBLE_MODEL_PLATFORM

        /** A provider this framework does not ship, reached through an application's own gateway. */
        const val GATEWAY_PROVIDER = "OurGateway"

        /** A provider no shipped platform handles: real, but not part of the BYOK surface. */
        const val UNHANDLED_PROVIDER = "Cohere"

        const val EMBEDDING_MODEL = "text-embedding-3-small"

        /**
         * Deliberately not any real model's width. `text-embedding-3-small` is 1536, so asserting
         * 1536 would pass just as well against a platform that assumed the catalogue default - which
         * is the bug this test exists to catch.
         */
        const val WIDTH_THE_PROVIDER_REPORTS = 7

        const val OVERRIDE_SERVICE_NAME = "from-the-application"
    }

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AgentByokAutoConfiguration::class.java))

    private fun platformsIn(context: ApplicationContext): List<ModelPlatform> =
        context.embeddingModelPlatforms().values.toList()

    /** Ask each platform in turn for an embedding model, exactly as `ConfigurableModelProvider` does. */
    private fun build(
        platforms: List<ModelPlatform>,
        provider: String,
        model: String = EMBEDDING_MODEL,
    ): EmbeddingService? = platforms.firstNotNullOfOrNull {
        it.model(ProviderCredential(provider, TEST_API_KEY), EmbeddingService.KIND, model)
    }

    @Test
    fun `the starter contributes one embedding platform, for the OpenAI protocol`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.embeddingModelPlatforms())
                .containsOnlyKeys(EMBEDDING_PLATFORM_BEAN)
        }
    }

    @Test
    fun `an Anthropic key gets nothing rather than somebody else's model`() {
        /*
         * Anthropic has no embedding API, so the Anthropic platform deliberately builds chat only.
         * Returning null is the whole of the correct behaviour: answering would point a client
         * holding an Anthropic key at an OpenAI endpoint.
         */
        contextRunner.run { context ->
            assertThat(build(platformsIn(context), AnthropicModels.PROVIDER)).isNull()
        }
    }

    @Test
    fun `a provider nothing routes returns null rather than a wrong service`() {
        contextRunner.run { context ->
            assertThat(build(platformsIn(context), UNHANDLED_PROVIDER)).isNull()
        }
    }

    @Test
    fun `the OpenAI module absent from the classpath contributes no embedding platform`() {
        // The provider modules are optional dependencies; this one must stay startable without them.
        contextRunner
            .withClassLoader(FilteredClassLoader(OpenAiCompatibleModelFactory::class.java))
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.embeddingModelPlatforms()).isEmpty()
            }
    }

    @Test
    fun `the Anthropic module absent changes nothing, since there is no Anthropic counterpart`() {
        contextRunner
            .withClassLoader(FilteredClassLoader(AnthropicModelFactory::class.java))
            .run { context ->
                assertThat(context.embeddingModelPlatforms())
                    .containsOnlyKeys(EMBEDDING_PLATFORM_BEAN)
            }
    }

    @Test
    fun `an application platform of the same name replaces the shipped one for every kind`() {
        contextRunner.withUserConfiguration(OwnEmbeddingPlatform::class.java).run { context ->
            val platforms = platformsIn(context)
            // Only the application's platform builds embedding models now.
            assertThat(platforms).hasSize(1)
            // The model comes from the application's platform, not the shipped one.
            assertThat(build(platforms, GATEWAY_PROVIDER)?.name).isEqualTo(OVERRIDE_SERVICE_NAME)
            // The replacement builds embedding only, so the shipped OpenAI-compatible chat models
            // went with the bean it replaced: only Anthropic's platform still builds chat.
            assertThat(context.chatModelPlatforms()).containsOnlyKeys(CredentialEndpointConfig.ANTHROPIC_MODEL_PLATFORM)
        }
    }

    @Test
    fun `the probe goes to the resolver's base URL, and the width is what came back`() {
        /*
         * The two things no assertion on a built service can otherwise see. Dropping `baseUrl` on
         * the way to the client would leave every other test here green and send every user's key
         * to OpenAI; assuming the width instead of reading the response would leave a vector index
         * created at a width the provider never agreed to.
         */
        withEmbeddingProvider { probedPath, platforms ->
            val service = build(platforms, GATEWAY_PROVIDER)

            assertThat(probedPath.getNow(null)).describedAs("probe never reached the gateway").isNotNull()
            assertThat(service).isNotNull()
            assertThat(service?.provider).isEqualTo(GATEWAY_PROVIDER)
            assertThat(service?.name).isEqualTo(EMBEDDING_MODEL)
            assertThat(service?.dimensions)
                .describedAs("the width has to be observed, not assumed from the model name")
                .isEqualTo(WIDTH_THE_PROVIDER_REPORTS)
        }
    }

    @Test
    fun `a BYOK embedding call is not charged to the deployment`() {
        // The user's own key is billed, so counting it against the deployment would be wrong in the
        // one direction that matters.
        withEmbeddingProvider { _, platforms ->
            assertThat(build(platforms, GATEWAY_PROVIDER)?.pricingModel).isEqualTo(PricingModel.ALL_YOU_CAN_EAT)
        }
    }

    /**
     * Run [block] against a socket standing in for the gateway, with the shipped platform routed to
     * it - the only way to exercise a platform that probes without reaching the public internet.
     *
     * @param block given the path the probe arrived on, and the platforms the context contributed
     */
    private fun withEmbeddingProvider(
        block: (probedPath: CompletableFuture<String>, platforms: List<ModelPlatform>) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val probedPath = CompletableFuture<String>()
        try {
            server.createContext("/") { exchange ->
                probedPath.complete(exchange.requestURI.path)
                exchange.requestBody.use { it.readBytes() }
                val body = embeddingResponse(WIDTH_THE_PROVIDER_REPORTS).toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            OwnGatewayEndpoint.gatewayUrl.set("http://localhost:${server.address.port}")

            contextRunner.withUserConfiguration(OwnGatewayEndpoint::class.java).run { context ->
                block(probedPath, platformsIn(context))
            }
        } finally {
            server.stop(0)
        }
    }

    /** One OpenAI embeddings response, of the width the caller asks for. */
    private fun embeddingResponse(width: Int): String {
        val vector = (0 until width).joinToString(",") { "0.1" }
        return """
            {"object":"list",
             "model":"$EMBEDDING_MODEL",
             "data":[{"object":"embedding","index":0,"embedding":[$vector]}],
             "usage":{"prompt_tokens":1,"total_tokens":1}}
        """.trimIndent()
    }

    /** An application bringing a provider of its own: one value, no SPI import. */
    @Configuration(proxyBeanMethods = false)
    class OwnGatewayEndpoint {

        companion object {
            val gatewayUrl = AtomicReference("https://gateway.example.com/v1")
        }

        @Bean
        fun ourGatewayEndpoint() = CredentialEndpointResolver { credential, _ ->
            if (!credential.provider.equals(GATEWAY_PROVIDER, ignoreCase = true)) null
            else CredentialEndpoint.OpenAiCompatible(
                provider = GATEWAY_PROVIDER,
                baseUrl = gatewayUrl.get(),
                pricingModel = PricingModel.ALL_YOU_CAN_EAT,
            )
        }
    }

    /**
     * Stands in for an application whose provider embeds over some protocol this module does not
     * speak, registered under the shipped bean's name, which is how it takes precedence.
     */
    @Configuration(proxyBeanMethods = false)
    class OwnEmbeddingPlatform {

        @Bean(EMBEDDING_PLATFORM_BEAN)
        fun openAiCompatibleModelPlatform(): ModelPlatform = ModelPlatform.builder()
            .builds(EmbeddingService.KIND) { credential, _ ->
                if (!credential.provider.equals(GATEWAY_PROVIDER, ignoreCase = true)) null
                else object : EmbeddingService {
                    override val name = OVERRIDE_SERVICE_NAME
                    override val provider = GATEWAY_PROVIDER
                    override val pricingModel: PricingModel = PricingModel.ALL_YOU_CAN_EAT
                    override val dimensions = WIDTH_THE_PROVIDER_REPORTS
                    override fun embed(text: String) = FloatArray(dimensions)
                    override fun embed(texts: List<String>) = texts.map { FloatArray(dimensions) }
                }
            }
            .build()
    }
}
