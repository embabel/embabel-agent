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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.PlaceholderEmbeddingService
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ChatModel

/**
 * Embedding roles resolving per call, so a key that arrives after startup embeds the next
 * document rather than the next boot.
 *
 * The chat counterpart is [RoleResolutionTest]; the two are meant to read alike.
 */
class EmbeddingRoleResolutionTest {

    private companion object {

        /**
         * Catalogue ids, named rather than repeated: a model being retired should be one edit here
         * rather than a hunt through every assertion. The embedding names match
         * `OpenAiCompatibleModelFactoryByokEmbeddingTest`, which tests the other end of the same path.
         */
        const val SMALL_MODEL = "text-embedding-3-small"
        const val MISTRAL_MODEL = "mistral-embed"
        const val DEFAULT_LLM = "gpt-4.1-mini"

        /** A model name no catalogue holds, for the cases about a name that resolves to nothing. */
        const val IMAGINARY_MODEL = "text-embedding-9-imaginary"

        /** The role under test throughout. */
        const val DOCUMENTS_ROLE = "documents"

        /** What the placeholder registers under, which a default may name deliberately. */
        const val PLACEHOLDER_NAME = "setup-required-embedding"

        const val OPENAI = "openai"
        const val MISTRAL = "mistral"
    }

    private fun llm(name: String, provider: String): LlmService<*> =
        SpringAiLlmService(name, provider, mockk<ChatModel>(), DefaultOptionsConverter)

    private val defaultLlm = llm(DEFAULT_LLM, OPENAI)

    /** A registered embedding service that can actually embed. */
    private class FakeEmbeddingService(
        override val name: String,
        override val provider: String,
        override val dimensions: Int = 1536,
    ) : EmbeddingService {
        override val pricingModel: PricingModel? = null
        override fun embed(text: String) = FloatArray(dimensions)
        override fun embed(texts: List<String>) = texts.map { FloatArray(dimensions) }
        override fun infoString(verbose: Boolean?, indent: Int) = name
    }

    /**
     * Stands in for `SetupRequiredEmbedding`, which lives in the BYOK module this one cannot
     * depend on. Every member throws, as the real one does, so a test that accidentally used it
     * fails loudly rather than embedding zeroes.
     */
    private class FakePlaceholder : EmbeddingService, PlaceholderEmbeddingService {
        override val name = PLACEHOLDER_NAME
        override val provider = "none"
        override val pricingModel: PricingModel? = null
        override val awaitingProviderKey = true
        override fun embed(text: String): FloatArray = error("no embedding service is configured")
        override fun embed(texts: List<String>): List<FloatArray> = error("no embedding service is configured")
        override val dimensions: Int get() = error("no embedding service is configured")
        override fun infoString(verbose: Boolean?, indent: Int) = name
    }

    private val small = FakeEmbeddingService(SMALL_MODEL, OPENAI)
    private val mistral = FakeEmbeddingService(MISTRAL_MODEL, MISTRAL, dimensions = 1024)
    private val placeholder = FakePlaceholder()

    private fun provider(
        properties: ConfigurableModelProviderProperties,
        embeddings: List<EmbeddingService>,
        embeddingRoleResolvers: List<EmbeddingRoleResolver> = emptyList(),
        credentialEmbeddingServiceFactories: List<CredentialEmbeddingServiceFactory> = emptyList(),
    ) = ConfigurableModelProvider(
        llms = listOf(defaultLlm),
        embeddingServices = embeddings,
        properties = properties,
        embeddingRoleResolvers = embeddingRoleResolvers,
        credentialEmbeddingServiceFactories = credentialEmbeddingServiceFactories,
    )

    @Nested
    inner class DefaultNamingARole {

        @Test
        fun `a default naming a flat role resolves to the model that role names`() {
            val mp = provider(
                ConfigurableModelProviderProperties(
                    defaultLlm = DEFAULT_LLM,
                    embeddingServices = mapOf(DOCUMENTS_ROLE to SMALL_MODEL),
                    defaultEmbeddingModel = DOCUMENTS_ROLE,
                ),
                listOf(small, placeholder),
            )
            assertSame(small, mp.getEmbeddingService(DefaultModelSelectionCriteria))
        }

        @Test
        fun `a default naming a model is still resolved once, against registered services`() {
            val properties = ConfigurableModelProviderProperties(
                defaultLlm = DEFAULT_LLM,
                defaultEmbeddingModel = SMALL_MODEL,
            )
            assertFalse(properties.defaultEmbeddingModelNamesRole())
            assertSame(small, provider(properties, listOf(small)).getEmbeddingService(DefaultModelSelectionCriteria))
        }

        @Test
        fun `a default naming a nested role picks the column for the active key`() {
            val properties = ConfigurableModelProviderProperties(
                defaultLlm = DEFAULT_LLM,
                defaultEmbeddingModel = DOCUMENTS_ROLE,
                embeddingRoles = mapOf(
                    DOCUMENTS_ROLE to mapOf(
                        OPENAI to SMALL_MODEL,
                        MISTRAL to MISTRAL_MODEL,
                    ),
                ),
            )
            val mp = provider(
                properties,
                listOf(placeholder),
                credentialEmbeddingServiceFactories = listOf(
                    CredentialEmbeddingServiceFactory { credential, model ->
                        FakeEmbeddingService(model, credential.provider)
                    },
                ),
            )
            val resolved = ModelSelectionContextHolder.with(
                ModelSelectionContext(credential = ProviderCredential(MISTRAL, "sk-test")),
            ) { mp.getEmbeddingService(DefaultModelSelectionCriteria) }
            assertEquals(MISTRAL_MODEL, resolved.name)
            assertEquals(MISTRAL, resolved.provider)
        }
    }

    @Nested
    inner class AKeyThatArrivesAfterStartup {

        /**
         * The defect this whole change exists for: with no per-call resolution the answer here is
         * the placeholder for the life of the process, whatever key turns up later.
         */
        @Test
        fun `a resolver answering from its own store satisfies the role with no restart`() {
            var storedKey: String? = null
            val mp = provider(
                ConfigurableModelProviderProperties(
                    defaultLlm = DEFAULT_LLM,
                    defaultEmbeddingModel = DOCUMENTS_ROLE,
                    embeddingServices = mapOf(DOCUMENTS_ROLE to SMALL_MODEL),
                ),
                listOf(placeholder),
                embeddingRoleResolvers = listOf(
                    EmbeddingRoleResolver { _, _ ->
                        storedKey?.let { EmbeddingRoleResolution.Service(FakeEmbeddingService("stored", OPENAI)) }
                    },
                ),
            )

            assertTrue(
                mp.getEmbeddingService(DefaultModelSelectionCriteria).awaitingProviderKey,
                "before a key is stored the default must still be the placeholder",
            )

            storedKey = "sk-arrived-after-boot"

            val afterKey = mp.getEmbeddingService(DefaultModelSelectionCriteria)
            assertFalse(afterKey.awaitingProviderKey)
            assertEquals("stored", afterKey.name)
        }

        @Test
        fun `a service built from a credential is cached per provider, key and model`() {
            var builds = 0
            val mp = provider(
                ConfigurableModelProviderProperties(
                    defaultLlm = DEFAULT_LLM,
                    embeddingRoles = mapOf(DOCUMENTS_ROLE to mapOf(OPENAI to SMALL_MODEL)),
                ),
                listOf(placeholder),
                credentialEmbeddingServiceFactories = listOf(
                    CredentialEmbeddingServiceFactory { credential, model ->
                        builds++
                        FakeEmbeddingService(model, credential.provider)
                    },
                ),
            )
            val criteria = ByRoleModelSelectionCriteria(DOCUMENTS_ROLE)
            ModelSelectionContextHolder.with(
                ModelSelectionContext(credential = ProviderCredential(OPENAI, "sk-same")),
            ) {
                mp.getEmbeddingService(criteria)
                mp.getEmbeddingService(criteria)
            }
            assertEquals(1, builds, "the second call must reuse the cached service")

            ModelSelectionContextHolder.with(
                ModelSelectionContext(credential = ProviderCredential(OPENAI, "sk-rotated")),
            ) { mp.getEmbeddingService(criteria) }
            assertEquals(2, builds, "a rotated key must not reuse the service built for the old one")
        }
    }

    @Nested
    inner class RolesAskedForDirectly {

        @Test
        fun `a role resolves through the chain rather than the registered map alone`() {
            val mp = provider(
                ConfigurableModelProviderProperties(
                    defaultLlm = DEFAULT_LLM,
                    embeddingServices = mapOf(DOCUMENTS_ROLE to SMALL_MODEL),
                ),
                listOf(small, mistral),
            )
            assertSame(small, mp.getEmbeddingService(ByRoleModelSelectionCriteria(DOCUMENTS_ROLE)))
        }

        @Test
        fun `an application resolver beats configuration`() {
            val mp = provider(
                ConfigurableModelProviderProperties(
                    defaultLlm = DEFAULT_LLM,
                    embeddingServices = mapOf(DOCUMENTS_ROLE to SMALL_MODEL),
                ),
                listOf(small, mistral),
                embeddingRoleResolvers = listOf(
                    EmbeddingRoleResolver { role, _ ->
                        if (role == DOCUMENTS_ROLE) EmbeddingRoleResolution.Model(MISTRAL_MODEL) else null
                    },
                ),
            )
            assertSame(mistral, mp.getEmbeddingService(ByRoleModelSelectionCriteria(DOCUMENTS_ROLE)))
        }

        /**
         * Both ways round, keyed or awaiting: an embedding model is a schema commitment, so an
         * unsatisfied role throws rather than degrading to the placeholder the way a chat role
         * does. See [ConfigurableModelProvider] for the asymmetry.
         */
        @Test
        fun `an unsatisfiable role throws even while awaiting a key`() {
            val mp = provider(
                ConfigurableModelProviderProperties(
                    defaultLlm = DEFAULT_LLM,
                    defaultEmbeddingModel = PLACEHOLDER_NAME,
                    embeddingServices = mapOf(DOCUMENTS_ROLE to SMALL_MODEL),
                ),
                listOf(placeholder),
            )
            assertThrows<NoSuitableModelException> {
                mp.getEmbeddingService(ByRoleModelSelectionCriteria(DOCUMENTS_ROLE))
            }
        }

        @Test
        fun `an unsatisfiable role throws where the deployment holds a key`() {
            val mp = provider(
                ConfigurableModelProviderProperties(defaultLlm = DEFAULT_LLM),
                listOf(small),
            )
            assertThrows<NoSuitableModelException> {
                mp.getEmbeddingService(ByRoleModelSelectionCriteria("nobody-configured-this"))
            }
        }

        /**
         * A chat resolver must never be consulted for an embedding role, which is the whole reason
         * [EmbeddingRoleResolution] is a separate type from [RoleResolution].
         */
        @Test
        fun `a chat role resolver is not asked about an embedding role of the same name`() {
            var chatResolverAsked = false
            val mp = ConfigurableModelProvider(
                llms = listOf(defaultLlm),
                embeddingServices = listOf(small),
                properties = ConfigurableModelProviderProperties(
                    defaultLlm = DEFAULT_LLM,
                    embeddingServices = mapOf("shared-name" to SMALL_MODEL),
                ),
                roleResolvers = listOf(
                    RoleResolver { _, _ ->
                        chatResolverAsked = true
                        RoleResolution.Service(defaultLlm)
                    },
                ),
            )
            assertSame(small, mp.getEmbeddingService(ByRoleModelSelectionCriteria("shared-name")))
            assertFalse(chatResolverAsked, "an embedding role must not reach the chat chain")
        }
    }

    /**
     * A caller that NAMES an embedding model must get that model or an error.
     *
     * Falling through to the default was silent: an application offering "change the embedding
     * model" reported success, re-embedded its whole corpus, and left the model as it was.
     */
    @Nested
    inner class AskingForOneByName {

        @Test
        @DisplayName("a registered model is returned, not the default")
        fun `by name returns the named model`() {
            val mp = provider(
                ConfigurableModelProviderProperties(
                    defaultLlm = "gpt-4.1-mini",
                    defaultEmbeddingModel = "text-embedding-3-small",
                ),
                listOf(small, mistral),
            )
            assertSame(mistral, mp.getEmbeddingService(ByNameModelSelectionCriteria("mistral-embed")))
        }

        @Test
        @DisplayName("a name nothing can serve throws, rather than quietly yielding the default")
        fun `an unknown name throws`() {
            val mp = provider(
                ConfigurableModelProviderProperties(
                    defaultLlm = "gpt-4.1-mini",
                    defaultEmbeddingModel = "text-embedding-3-small",
                ),
                listOf(small),
            )
            // The bug: this used to return `small`, so the caller re-embedded everything into the
            // model it already had and was told it had changed.
            assertThrows<NoSuitableModelException> {
                mp.getEmbeddingService(ByNameModelSelectionCriteria("text-embedding-3-large"))
            }
        }

        @Test
        @DisplayName("an unregistered name is built from the key the default role uses")
        fun `by name builds from the deployment credential`() {
            val mp = provider(
                ConfigurableModelProviderProperties(
                    defaultLlm = "gpt-4.1-mini",
                    defaultEmbeddingModel = "documents",
                    embeddingRoles = mapOf("documents" to mapOf("openai" to "text-embedding-3-small")),
                ),
                listOf(placeholder),
                embeddingRoleResolvers = listOf(
                    EmbeddingRoleResolver { _, _ ->
                        EmbeddingRoleResolution.Credential(ProviderCredential("openai", "sk-stored"))
                    },
                ),
                credentialEmbeddingServiceFactories = listOf(
                    CredentialEmbeddingServiceFactory { credential, model ->
                        FakeEmbeddingService(model, credential.provider, dimensions = 3072)
                    },
                ),
            )

            // The appliance case: nothing is registered at boot, and the key is the only way to
            // reach a model the deployment did not build with.
            val resolved = mp.getEmbeddingService(ByNameModelSelectionCriteria("text-embedding-3-large"))
            assertEquals("text-embedding-3-large", resolved.name)
            assertEquals(3072, resolved.dimensions)
        }
    }

    @Nested
    inner class WhatTheFallbackSays {

        private fun captureWarnings(block: () -> Unit): List<ILoggingEvent> {
            val logger = LoggerFactory.getLogger(ConfigurableModelProvider::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            logger.level = Level.DEBUG
            try {
                block()
            } finally {
                logger.detachAppender(appender)
            }
            return appender.list
        }

        /**
         * The reported defect: an appliance holding a working key, with no embedding model chosen,
         * was told it held no provider API key — and the operator debugged the key.
         */
        @Test
        fun `an unset default says the choice was never made, not that a key is missing`() {
            val events = captureWarnings {
                provider(
                    ConfigurableModelProviderProperties(defaultLlm = DEFAULT_LLM, defaultEmbeddingModel = null),
                    listOf(small, placeholder),
                ).getEmbeddingService(DefaultModelSelectionCriteria)
            }
            val message = events.single { it.level == Level.WARN }.formattedMessage
            assertTrue(message.contains("No embedding model is configured"), message)
            assertTrue(message.contains("CHOICE not yet made"), message)
            assertFalse(message.contains("provider API key"), message)
        }

        @Test
        fun `an empty default is treated the same as an unset one`() {
            val events = captureWarnings {
                provider(
                    ConfigurableModelProviderProperties(defaultLlm = DEFAULT_LLM, defaultEmbeddingModel = ""),
                    listOf(small, placeholder),
                ).getEmbeddingService(DefaultModelSelectionCriteria)
            }
            assertTrue(
                events.single { it.level == Level.WARN }.formattedMessage.contains("unset or empty"),
                "an empty override is how a container passes 'no value', and must not read as a typo",
            )
        }

        @Test
        fun `a role awaiting a key is reported as a role, not as a misconfiguration`() {
            val events = captureWarnings {
                provider(
                    ConfigurableModelProviderProperties(
                        defaultLlm = DEFAULT_LLM,
                        defaultEmbeddingModel = DOCUMENTS_ROLE,
                        embeddingServices = mapOf(DOCUMENTS_ROLE to SMALL_MODEL),
                    ),
                    listOf(placeholder),
                ).getEmbeddingService(DefaultModelSelectionCriteria)
            }
            assertTrue(
                events.any { it.formattedMessage.contains("is a role, and will be resolved per call") },
                events.joinToString { it.formattedMessage },
            )
            // Startup still reports that the role's model is not registered yet — that is accurate
            // and useful. What must NOT appear is the fallback blaming the default itself.
            assertTrue(
                events.none { it.formattedMessage.contains("is not registered; falling back") },
                events.joinToString { it.formattedMessage },
            )
        }

        /**
         * The default is resolved on every call that does not name a role, so a standing condition
         * would otherwise be stated once per embedded chunk - which is how the one line an operator
         * has to read gets filtered out.
         */
        @Test
        fun `a standing fallback is reported once, not once per call`() {
            val events = captureWarnings {
                val mp = provider(
                    ConfigurableModelProviderProperties(defaultLlm = DEFAULT_LLM, defaultEmbeddingModel = null),
                    listOf(small, placeholder),
                )
                repeat(5) { mp.getEmbeddingService(DefaultModelSelectionCriteria) }
            }
            assertEquals(
                1,
                events.count { it.formattedMessage.contains("No embedding model is configured") },
                events.joinToString { it.formattedMessage },
            )
        }

        @Test
        fun `a fallback for a different reason is reported again`() {
            val properties = ConfigurableModelProviderProperties(
                defaultLlm = DEFAULT_LLM,
                defaultEmbeddingModel = null,
            )
            val events = captureWarnings {
                val mp = provider(properties, listOf(small, placeholder))
                mp.getEmbeddingService(DefaultModelSelectionCriteria)
                mp.getEmbeddingService(DefaultModelSelectionCriteria)
                properties.defaultEmbeddingModel = IMAGINARY_MODEL
                mp.getEmbeddingService(DefaultModelSelectionCriteria)
            }
            assertEquals(
                1,
                events.count { it.formattedMessage.contains("No embedding model is configured") },
                events.joinToString { it.formattedMessage },
            )
            assertEquals(
                1,
                events.count { it.formattedMessage.contains("is not registered; falling back") },
                "suppression must be per reason, not per process",
            )
        }

        @Test
        fun `a genuinely unregistered model name still warns that it is not registered`() {
            val events = captureWarnings {
                provider(
                    ConfigurableModelProviderProperties(
                        defaultLlm = DEFAULT_LLM,
                        defaultEmbeddingModel = IMAGINARY_MODEL,
                    ),
                    listOf(small, placeholder),
                ).getEmbeddingService(DefaultModelSelectionCriteria)
            }
            assertTrue(
                events.single { it.level == Level.WARN }.formattedMessage.contains("is not registered"),
                "a typo must still be reported as one",
            )
        }
    }

    @Nested
    inner class Configuration {

        @Test
        fun `a role is only a role when configuration says so`() {
            assertFalse(
                ConfigurableModelProviderProperties(defaultEmbeddingModel = SMALL_MODEL)
                    .defaultEmbeddingModelNamesRole(),
            )
            assertTrue(
                ConfigurableModelProviderProperties(
                    defaultEmbeddingModel = DOCUMENTS_ROLE,
                    embeddingServices = mapOf(DOCUMENTS_ROLE to SMALL_MODEL),
                ).defaultEmbeddingModelNamesRole(),
            )
            assertTrue(
                ConfigurableModelProviderProperties(
                    defaultEmbeddingModel = DOCUMENTS_ROLE,
                    embeddingRoles = mapOf(DOCUMENTS_ROLE to mapOf(OPENAI to SMALL_MODEL)),
                ).defaultEmbeddingModelNamesRole(),
            )
        }

        @Test
        fun `a role name is not offered as a model name`() {
            val names = ConfigurableModelProviderProperties(
                defaultEmbeddingModel = DOCUMENTS_ROLE,
                embeddingRoles = mapOf(DOCUMENTS_ROLE to mapOf(OPENAI to SMALL_MODEL)),
            ).allWellKnownEmbeddingServiceNames()
            assertEquals(setOf(SMALL_MODEL), names)
        }

        /**
         * With no active key the provider has to be inferred from what is registered, and
         * [ConfigurableModelProvider] answers only where that is unambiguous. `mistral` is listed
         * FIRST here deliberately: a first-one-wins tie-break would read the mistral column and
         * return `mistral-embed`, and would return something else again on a deployment that
         * registered its modules in another order.
         */
        @Test
        fun `two registered providers and no key takes the flat map, not an arbitrary column`() {
            val mp = provider(
                ConfigurableModelProviderProperties(
                    defaultLlm = DEFAULT_LLM,
                    embeddingServices = mapOf(DOCUMENTS_ROLE to SMALL_MODEL),
                    embeddingRoles = mapOf(
                        DOCUMENTS_ROLE to mapOf(OPENAI to SMALL_MODEL, MISTRAL to MISTRAL_MODEL),
                    ),
                ),
                listOf(mistral, small),
            )
            assertSame(small, mp.getEmbeddingService(ByRoleModelSelectionCriteria(DOCUMENTS_ROLE)))
        }

        @Test
        fun `one registered provider and no key still reads that provider's column`() {
            val mp = provider(
                ConfigurableModelProviderProperties(
                    defaultLlm = DEFAULT_LLM,
                    embeddingServices = mapOf(DOCUMENTS_ROLE to SMALL_MODEL),
                    embeddingRoles = mapOf(DOCUMENTS_ROLE to mapOf(MISTRAL to MISTRAL_MODEL)),
                ),
                listOf(mistral, placeholder),
            )
            assertSame(mistral, mp.getEmbeddingService(ByRoleModelSelectionCriteria(DOCUMENTS_ROLE)))
        }

        @Test
        fun `the nested map wins over the flat one for the active provider`() {
            val resolver = ConfigurableEmbeddingRoleResolver(
                ConfigurableModelProviderProperties(
                    embeddingServices = mapOf(DOCUMENTS_ROLE to SMALL_MODEL),
                    embeddingRoles = mapOf(DOCUMENTS_ROLE to mapOf(MISTRAL to MISTRAL_MODEL)),
                ),
            ) { OPENAI }
            assertEquals(MISTRAL_MODEL, resolver.configuredModelFor(DOCUMENTS_ROLE, MISTRAL))
            assertEquals(SMALL_MODEL, resolver.configuredModelFor(DOCUMENTS_ROLE, OPENAI))
            assertNull(resolver.configuredModelFor("no-such-role", OPENAI))
        }
    }
}
