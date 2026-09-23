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

import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.PlaceholderEmbeddingService
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.chat.model.ChatModel
import org.springframework.core.Ordered
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import com.embabel.common.ai.model.local.LocalModel
import com.embabel.common.ai.model.local.LocalModelBeans
import com.embabel.common.ai.model.local.LocalModelCatalog
import com.embabel.common.ai.model.local.LocalModelDiscoveryProperties
import com.embabel.common.ai.model.local.LocalModelEmbeddingRoleResolver
import com.embabel.common.ai.model.local.LocalModelKind
import com.embabel.common.ai.model.local.LocalModelRoleResolver
import com.embabel.common.ai.model.local.LocalModelSource

/**
 * A locally served model that appears AFTER startup being usable without a restart.
 *
 * The runner counterpart of [EmbeddingRoleResolutionTest], which covers the same defect for a
 * provider KEY that arrives late. The two are the same story from either end: the platform captured
 * what it could serve once, while it was being built, and everything that turned up afterwards was
 * invisible until the process came back.
 */
class LocalModelResolutionTest {

    private companion object {
        const val DOCKER = "docker"
        const val OPENAI = "openai"

        /** Pulled after boot, in every test here. */
        const val PULLED_EMBEDDING = "ai/embeddinggemma"
        const val PULLED_LLM = "ai/qwen3"

        const val DOCUMENTS_ROLE = "documents"
        const val CHEAPEST_ROLE = "cheapest"

        const val DEFAULT_LLM = "gpt-4.1-mini"
        const val PLACEHOLDER_NAME = "setup-required-embedding"

        /** Registered at startup, so the listings have something to be added TO. */
        const val REGISTERED_EMBEDDING = "text-embedding-3-small"
    }

    /** A runner under the test's control: what it serves, and how often it has been asked. */
    private class FakeRunner(
        override val provider: String = DOCKER,
        var serving: Set<LocalModel> = emptySet(),
        var reachable: Boolean = true,
        /**
         * A runner that LISTS a model but cannot build a service for it - a real one does this when
         * it can tell a chat model from an embedding model and the caller asked for the wrong half.
         */
        var buildsNothing: Boolean = false,
    ) : LocalModelSource {

        var listings = 0
            private set
        var llmBuilds = 0
            private set
        var embeddingBuilds = 0
            private set

        override fun servedModels(): Set<LocalModel> {
            listings++
            // An unreachable runner answers nothing rather than throwing, as the real ones do.
            return if (reachable) serving else emptySet()
        }

        override fun llmService(model: String): LlmService<*>? {
            llmBuilds++
            if (buildsNothing) return null
            return SpringAiLlmService(model, provider, mockk<ChatModel>(), DefaultOptionsConverter)
        }

        override fun embeddingService(model: String): EmbeddingService? {
            embeddingBuilds++
            if (buildsNothing) return null
            return FakeEmbeddingService(model, provider)
        }
    }

    private class FakeEmbeddingService(
        override val name: String,
        override val provider: String,
        override val dimensions: Int = 768,
    ) : EmbeddingService {
        override val pricingModel: PricingModel? = null
        override fun embed(text: String) = FloatArray(dimensions)
        override fun embed(texts: List<String>) = texts.map { FloatArray(dimensions) }
        override fun infoString(verbose: Boolean?, indent: Int) = name
    }

    /** Stands in for `SetupRequiredEmbedding`, which lives in a module this one cannot depend on. */
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

    /** A tick the test advances by hand, so an interval is asserted rather than waited out. */
    private class FakeTicker : () -> Long {
        private val nanos = AtomicLong(0)
        override fun invoke(): Long = nanos.get()
        fun advance(duration: Duration) = nanos.addAndGet(duration.toNanos())
    }

    private fun chat(vararg names: String) = names.map { LocalModel(it, LocalModelKind.CHAT) }.toSet()

    private fun embedding(vararg names: String) = names.map { LocalModel(it, LocalModelKind.EMBEDDING) }.toSet()

    private val ticker = FakeTicker()
    private val discovery = LocalModelDiscoveryProperties()
    private val defaultLlm: LlmService<*> =
        SpringAiLlmService(DEFAULT_LLM, OPENAI, mockk<ChatModel>(), DefaultOptionsConverter)
    private val placeholder = FakePlaceholder()
    private val registeredEmbedding = FakeEmbeddingService(REGISTERED_EMBEDDING, OPENAI)

    private fun catalog(runner: FakeRunner) = LocalModelCatalog(runner, discovery, ticker)

    /**
     * A deployment whose only embedding model is local: nothing registered but the placeholder, and
     * `default-embedding-model` naming a role that names the model under the runner's column.
     */
    private fun embeddingProvider(runner: FakeRunner): ConfigurableModelProvider {
        val properties = ConfigurableModelProviderProperties(
            defaultLlm = DEFAULT_LLM,
            defaultEmbeddingModel = DOCUMENTS_ROLE,
            embeddingRoles = mapOf(DOCUMENTS_ROLE to mapOf(DOCKER to PULLED_EMBEDDING)),
        )
        return ConfigurableModelProvider(
            llms = listOf(defaultLlm),
            embeddingServices = listOf(placeholder),
            properties = properties,
            embeddingRoleResolvers = listOf(LocalModelEmbeddingRoleResolver(catalog(runner), properties)),
        )
    }

    private fun llmProvider(
        runner: FakeRunner,
        roleOptions: LlmOptions = LlmOptions.withModel(PULLED_LLM),
    ): ConfigurableModelProvider {
        val properties = ConfigurableModelProviderProperties(
            defaultLlm = DEFAULT_LLM,
            roles = mapOf(CHEAPEST_ROLE to mapOf(DOCKER to roleOptions)),
        )
        return ConfigurableModelProvider(
            llms = listOf(defaultLlm),
            embeddingServices = listOf(placeholder),
            properties = properties,
            roleResolvers = listOf(LocalModelRoleResolver(catalog(runner), properties)),
        )
    }

    @Nested
    inner class AModelPulledAfterStartup {

        /**
         * The defect. Startup discovery registered nothing, so before this the answer was the
         * placeholder for the life of the process however plainly the runner was serving the model.
         */
        @Test
        fun `an embedding model pulled after boot satisfies the role with no restart`() {
            val runner = FakeRunner()
            val mp = embeddingProvider(runner)

            assertTrue(
                mp.getEmbeddingService(DefaultModelSelectionCriteria).awaitingProviderKey,
                "before the model is pulled the default must still be the placeholder",
            )

            runner.serving = embedding(PULLED_EMBEDDING)
            ticker.advance(discovery.refreshInterval.plusMillis(1))

            val afterPull = mp.getEmbeddingService(DefaultModelSelectionCriteria)
            assertFalse(afterPull.awaitingProviderKey)
            assertEquals(PULLED_EMBEDDING, afterPull.name)
            assertEquals(DOCKER, afterPull.provider)
        }

        @Test
        fun `a chat model pulled after boot satisfies the role with no restart`() {
            val runner = FakeRunner()
            val mp = llmProvider(runner)
            val criteria = ByRoleModelSelectionCriteria(CHEAPEST_ROLE)

            assertThrows<NoSuitableModelException> { mp.getLlm(criteria) }

            runner.serving = chat(PULLED_LLM)
            ticker.advance(discovery.refreshInterval.plusMillis(1))

            assertEquals(PULLED_LLM, mp.getLlm(criteria).name)
        }

        /**
         * A miss looks again immediately, so `pull` followed by a request works on the FIRST
         * request rather than after the refresh interval has elapsed.
         */
        @Test
        fun `a model that appears within the refresh interval is found on the next call`() {
            val runner = FakeRunner()
            val mp = embeddingProvider(runner)
            assertTrue(mp.getEmbeddingService(DefaultModelSelectionCriteria).awaitingProviderKey)

            runner.serving = embedding(PULLED_EMBEDDING)
            // Well inside refreshInterval, so only the miss path can find this - which is what
            // makes `pull` followed by a request work on the first request rather than the second.
            ticker.advance(discovery.missRefreshInterval)

            assertEquals(PULLED_EMBEDDING, mp.getEmbeddingService(DefaultModelSelectionCriteria).name)
        }
    }

    @Nested
    inner class WhatTheResolverDeclines {

        @Test
        fun `a role naming nothing under this provider is left to the rest of the chain`() {
            val properties = ConfigurableModelProviderProperties(
                defaultLlm = DEFAULT_LLM,
                embeddingRoles = mapOf(DOCUMENTS_ROLE to mapOf(OPENAI to "text-embedding-3-small")),
            )
            val runner = FakeRunner(serving = embedding(PULLED_EMBEDDING))
            val resolver = LocalModelEmbeddingRoleResolver(catalog(runner), properties)

            assertNull(resolver.resolve(DOCUMENTS_ROLE, ModelSelectionContext.EMPTY))
            assertEquals(0, runner.listings, "a role this provider says nothing about must not be an HTTP call")
        }

        @Test
        fun `an unreachable runner declines rather than failing`() {
            val runner = FakeRunner(serving = embedding(PULLED_EMBEDDING), reachable = false)
            val mp = embeddingProvider(runner)

            assertTrue(mp.getEmbeddingService(DefaultModelSelectionCriteria).awaitingProviderKey)
        }

        @Test
        fun `a user key for another provider is served by that provider, not by this machine`() {
            val properties = ConfigurableModelProviderProperties(
                defaultLlm = DEFAULT_LLM,
                embeddingRoles = mapOf(DOCUMENTS_ROLE to mapOf(DOCKER to PULLED_EMBEDDING)),
            )
            val runner = FakeRunner(serving = embedding(PULLED_EMBEDDING))
            val resolver = LocalModelEmbeddingRoleResolver(catalog(runner), properties)

            val underUserKey = ModelSelectionContext(credential = ProviderCredential(OPENAI, "sk-user"))
            assertNull(resolver.resolve(DOCUMENTS_ROLE, underUserKey))
            assertEquals(0, runner.listings, "a key for another provider must not even reach the runner")
        }

        @Test
        fun `disabling local discovery restores the startup-only behaviour`() {
            discovery.enabled = false
            val runner = FakeRunner(serving = embedding(PULLED_EMBEDDING))

            assertTrue(embeddingProvider(runner).getEmbeddingService(DefaultModelSelectionCriteria).awaitingProviderKey)
            assertEquals(0, runner.listings, "disabled means the runner is never asked")
        }
    }

    @Nested
    inner class WhatItCostsToAskPerCall {

        @Test
        fun `a hit inside the refresh interval does not ask the runner again`() {
            val runner = FakeRunner(serving = embedding(PULLED_EMBEDDING))
            val mp = embeddingProvider(runner)

            repeat(50) { mp.getEmbeddingService(DefaultModelSelectionCriteria) }

            assertEquals(1, runner.listings, "a cached list is what stops this being a request per embedding")
        }

        @Test
        fun `the runner is asked again once the refresh interval has elapsed`() {
            val runner = FakeRunner(serving = embedding(PULLED_EMBEDDING))
            val mp = embeddingProvider(runner)
            mp.getEmbeddingService(DefaultModelSelectionCriteria)

            ticker.advance(discovery.refreshInterval.plusMillis(1))
            mp.getEmbeddingService(DefaultModelSelectionCriteria)

            assertEquals(2, runner.listings)
        }

        @Test
        fun `a miss does not re-ask more often than the miss interval allows`() {
            val runner = FakeRunner()
            val mp = embeddingProvider(runner)

            repeat(50) { mp.getEmbeddingService(DefaultModelSelectionCriteria) }

            assertEquals(
                1, runner.listings,
                "a role nothing will ever satisfy must not turn every call into a request",
            )
        }

        @Test
        fun `the service is built once and then reused`() {
            val runner = FakeRunner(serving = embedding(PULLED_EMBEDDING))
            val mp = embeddingProvider(runner)

            val first = mp.getEmbeddingService(DefaultModelSelectionCriteria)
            val second = mp.getEmbeddingService(DefaultModelSelectionCriteria)

            assertSame(first, second)
            assertEquals(1, runner.embeddingBuilds)
        }

        @Test
        fun `one catalog serves both chains, so the runner is asked once for chat and embeddings`() {
            val runner = FakeRunner(serving = chat(PULLED_LLM) + embedding(PULLED_EMBEDDING))
            val properties = ConfigurableModelProviderProperties(
                defaultLlm = DEFAULT_LLM,
                defaultEmbeddingModel = DOCUMENTS_ROLE,
                roles = mapOf(CHEAPEST_ROLE to mapOf(DOCKER to LlmOptions.withModel(PULLED_LLM))),
                embeddingRoles = mapOf(DOCUMENTS_ROLE to mapOf(DOCKER to PULLED_EMBEDDING)),
            )
            val shared = catalog(runner)
            val mp = ConfigurableModelProvider(
                llms = listOf(defaultLlm),
                embeddingServices = listOf(placeholder),
                properties = properties,
                roleResolvers = listOf(LocalModelRoleResolver(shared, properties)),
                embeddingRoleResolvers = listOf(LocalModelEmbeddingRoleResolver(shared, properties)),
            )

            assertEquals(PULLED_LLM, mp.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE)).name)
            assertEquals(PULLED_EMBEDDING, mp.getEmbeddingService(DefaultModelSelectionCriteria).name)

            assertEquals(1, runner.listings)
        }
    }

    @Nested
    inner class ListingAndAskingByName {

        /**
         * A deployment with a registered model of each kind, and a runner alongside it. The
         * catalog is the same object the resolvers would use, so listing and resolution cannot
         * report different things.
         */
        private fun provider(runner: FakeRunner): ConfigurableModelProvider {
            val properties = ConfigurableModelProviderProperties(defaultLlm = DEFAULT_LLM)
            return ConfigurableModelProvider(
                llms = listOf(defaultLlm),
                embeddingServices = listOf(registeredEmbedding, placeholder),
                properties = properties,
                localModelCatalogs = listOf(catalog(runner)),
            )
        }

        /**
         * The reason listing had to move: an operator picks a model from this list, so one that
         * showed only what was captured at boot would leave a freshly pulled model invisible to
         * the very UI meant to select it.
         */
        @Test
        fun `a model pulled after boot is listed alongside the registered ones`() {
            val mp = provider(FakeRunner(serving = chat(PULLED_LLM) + embedding(PULLED_EMBEDDING)))

            assertEquals(
                listOf(DEFAULT_LLM, PULLED_LLM),
                mp.listModelNames(LlmService::class.java),
            )
            assertEquals(
                listOf(REGISTERED_EMBEDDING, PLACEHOLDER_NAME, PULLED_EMBEDDING),
                mp.listModelNames(EmbeddingService::class.java),
            )
        }

        @Test
        fun `a listed model answers to a request by name`() {
            val mp = provider(FakeRunner(serving = chat(PULLED_LLM) + embedding(PULLED_EMBEDDING)))

            assertEquals(PULLED_LLM, mp.getLlm(ByNameModelSelectionCriteria(PULLED_LLM)).name)
            assertEquals(
                PULLED_EMBEDDING,
                mp.getEmbeddingService(ByNameModelSelectionCriteria(PULLED_EMBEDDING)).name,
            )
        }

        /**
         * Kind decides which list a name is in, so a chat model cannot answer an embedding request
         * by name - the one place a runner's classification is allowed to refuse, because a caller
         * naming a model has given no other signal for which of the two they meant.
         */
        @Test
        fun `a chat model does not answer an embedding request by name`() {
            val mp = provider(FakeRunner(serving = chat(PULLED_LLM)))

            assertFalse(mp.listModelNames(EmbeddingService::class.java).contains(PULLED_LLM))
            assertThrows<NoSuitableModelException> {
                mp.getEmbeddingService(ByNameModelSelectionCriteria(PULLED_LLM))
            }
        }

        @Test
        fun `a model registered at startup is listed once, not twice`() {
            val mp = provider(FakeRunner(serving = chat(DEFAULT_LLM)))

            assertEquals(listOf(DEFAULT_LLM), mp.listModelNames(LlmService::class.java))
            assertEquals(1, mp.listModels().count { it.name == DEFAULT_LLM })
        }

        /**
         * Metadata for a late model carries its name and provider and nothing invented: the rest is
         * read off a built service, and building one per entry would turn a listing into a round of
         * work against the runner.
         */
        @Test
        fun `a late model is described by name and provider`() {
            val mp = provider(FakeRunner(serving = chat(PULLED_LLM)))

            val metadata = mp.listModels().single { it.name == PULLED_LLM }
            assertEquals(DOCKER, metadata.provider)
        }

        @Test
        fun `an unreachable runner adds nothing to the listing`() {
            val runner = FakeRunner(serving = chat(PULLED_LLM), reachable = false)

            assertEquals(listOf(DEFAULT_LLM), provider(runner).listModelNames(LlmService::class.java))
        }

        /**
         * The other two by-name criteria. A caller picking from a listing may pass several names,
         * and a late model has to answer to those the same way it answers to one - otherwise the
         * listing offers a name that only ONE of three selection paths honours.
         */
        @Test
        fun `a late model answers a fallback list, in order`() {
            val mp = provider(FakeRunner(serving = chat(PULLED_LLM)))

            assertEquals(
                PULLED_LLM,
                mp.getLlm(FallbackByNameModelSelectionCriteria(listOf("never-pulled", PULLED_LLM))).name,
                "the first name misses, so the second must be tried",
            )
        }

        @Test
        fun `a late model can be drawn from a random-by-name set`() {
            val mp = provider(FakeRunner(serving = chat(PULLED_LLM)))

            assertEquals(
                PULLED_LLM,
                mp.getLlm(RandomByNameModelSelectionCriteria(listOf(PULLED_LLM))).name,
            )
        }

        /**
         * What a failure says is available must be the same set the listing reports, or an operator
         * is told a model is unavailable in the same breath as being offered it.
         */
        @Test
        fun `a failure names the late model among what is available`() {
            val mp = provider(FakeRunner(serving = chat(PULLED_LLM)))

            for (criteria in listOf(
                ByNameModelSelectionCriteria("never-pulled"),
                FallbackByNameModelSelectionCriteria(listOf("never-pulled")),
                RandomByNameModelSelectionCriteria(listOf("never-pulled")),
            )) {
                val thrown = assertThrows<NoSuitableModelException> { mp.getLlm(criteria) }
                assertTrue(
                    thrown.message!!.contains(PULLED_LLM),
                    "$criteria must offer the same names the listing does, but said: ${thrown.message}",
                )
            }
        }

        @Test
        fun `disabling local discovery leaves the listing as it was`() {
            discovery.enabled = false
            val runner = FakeRunner(serving = chat(PULLED_LLM))

            assertEquals(listOf(DEFAULT_LLM), provider(runner).listModelNames(LlmService::class.java))
            assertEquals(0, runner.listings)
        }
    }

    @Nested
    inner class DefaultsNamingALocalModel {

        /**
         * What an appliance actually sets. `default-embedding-model` naming a MODEL is resolved
         * once at startup, so before this it stayed the placeholder for the life of the process
         * however plainly the runner was serving the model afterwards.
         */
        @Test
        fun `a default naming a model the runner starts serving resolves without a restart`() {
            val runner = FakeRunner()
            val properties = ConfigurableModelProviderProperties(
                defaultLlm = DEFAULT_LLM,
                defaultEmbeddingModel = PULLED_EMBEDDING,
            )
            val mp = ConfigurableModelProvider(
                llms = listOf(defaultLlm),
                embeddingServices = listOf(placeholder),
                properties = properties,
                localModelCatalogs = listOf(catalog(runner)),
            )

            assertTrue(mp.getEmbeddingService(DefaultModelSelectionCriteria).awaitingProviderKey)

            runner.serving = embedding(PULLED_EMBEDDING)
            ticker.advance(discovery.refreshInterval.plusMillis(1))

            assertEquals(PULLED_EMBEDDING, mp.getEmbeddingService(DefaultModelSelectionCriteria).name)
        }

        /**
         * A `default-llm` naming a ROLE is resolved by the chain, which already contains the local
         * resolvers - so asking the runners for a model of that name on top can only ever miss, and
         * a miss is an HTTP request. Under BYOK the default resolves to nothing on every call made
         * before a key arrives, so an unguarded probe would be a listing per second, forever,
         * against a runner that could never answer.
         */
        @Test
        fun `a default naming a role does not ask the runner for a model of that name`() {
            val runner = FakeRunner()
            val properties = ConfigurableModelProviderProperties(
                defaultLlm = CHEAPEST_ROLE,
                llms = mapOf(CHEAPEST_ROLE to DEFAULT_LLM),
            )
            val mp = ConfigurableModelProvider(
                llms = listOf(defaultLlm),
                embeddingServices = listOf(placeholder),
                properties = properties,
                localModelCatalogs = listOf(catalog(runner)),
            )

            mp.getLlm(DefaultModelSelectionCriteria)

            assertEquals(0, runner.listings, "a role is the chain's business, not a model name to look up")
        }
    }

    /**
     * The seams the resolvers present to the rest of the platform: where they sort, and what they do
     * when the runner contradicts itself by listing a model it cannot build.
     */
    @Nested
    inner class HowTheResolversBehaveInTheChain {

        private fun properties() = ConfigurableModelProviderProperties(
            defaultLlm = DEFAULT_LLM,
            roles = mapOf(CHEAPEST_ROLE to mapOf(DOCKER to LlmOptions.withModel(PULLED_LLM))),
            embeddingRoles = mapOf(DOCUMENTS_ROLE to mapOf(DOCKER to PULLED_EMBEDDING)),
        )

        /**
         * Last, so an application that has decided what a role means keeps deciding it. A resolver
         * sorting anywhere earlier would take roles away from the application that configured them.
         */
        @Test
        fun `both resolvers sort last, behind any application resolver`() {
            val properties = properties()
            val runnerCatalog = catalog(FakeRunner())

            assertEquals(
                Ordered.LOWEST_PRECEDENCE,
                LocalModelRoleResolver(runnerCatalog, properties).order,
            )
            assertEquals(
                Ordered.LOWEST_PRECEDENCE,
                LocalModelEmbeddingRoleResolver(runnerCatalog, properties).order,
            )
        }

        /**
         * A chat role under a user key for ANOTHER provider: the user asked for their provider and
         * expects to be billed for it, so answering off this machine would answer with the wrong
         * model. Asserted for the chat side as well as the embedding side, since the rule is shared
         * and a shared rule is exactly the kind that gets broken for one caller only.
         */
        @Test
        fun `a chat role under a user key for another provider is declined`() {
            val properties = properties()
            val runner = FakeRunner(serving = chat(PULLED_LLM))
            val resolver = LocalModelRoleResolver(catalog(runner), properties)

            val underUserKey = ModelSelectionContext(credential = ProviderCredential(OPENAI, "sk-user"))

            assertNull(resolver.resolve(CHEAPEST_ROLE, underUserKey))
            assertEquals(0, runner.listings, "a key for another provider must not even reach the runner")
        }

        /**
         * A runner that lists a model and then cannot build a service for it has contradicted
         * itself. Declining passes the role to the rest of the chain; returning a half-built
         * resolution would fail later, somewhere that cannot say which runner caused it.
         */
        @Test
        fun `a model listed but not buildable declines rather than resolving`() {
            val properties = properties()
            val runner = FakeRunner(serving = chat(PULLED_LLM), buildsNothing = true)

            val resolved = LocalModelRoleResolver(catalog(runner), properties)
                .resolve(CHEAPEST_ROLE, ModelSelectionContext.EMPTY)

            assertNull(resolved)
            assertEquals(1, runner.llmBuilds, "it must have tried, or the decline means nothing")
        }

        @Test
        fun `an embedding model listed but not buildable declines rather than resolving`() {
            val properties = properties()
            val runner = FakeRunner(serving = embedding(PULLED_EMBEDDING), buildsNothing = true)

            val resolved = LocalModelEmbeddingRoleResolver(catalog(runner), properties)
                .resolve(DOCUMENTS_ROLE, ModelSelectionContext.EMPTY)

            assertNull(resolved)
            assertEquals(1, runner.embeddingBuilds, "it must have tried, or the decline means nothing")
        }

        /**
         * What a runner module publishes. The three are built over ONE catalog, so a runner asked
         * for a chat role and an embedding role in the same interval is asked once.
         */
        @Test
        fun `the published beans share a single catalog`() {
            val runner = FakeRunner(serving = chat(PULLED_LLM) + embedding(PULLED_EMBEDDING))
            val beans = LocalModelBeans(runner, properties(), discovery)

            assertNotNull(beans.roleResolver.resolve(CHEAPEST_ROLE, ModelSelectionContext.EMPTY))
            assertNotNull(beans.embeddingRoleResolver.resolve(DOCUMENTS_ROLE, ModelSelectionContext.EMPTY))

            assertEquals(1, runner.listings, "two resolvers over two catalogs would be two listings")
            assertEquals(DOCKER, beans.catalog.provider)
        }
    }

    /**
     * What a model a runner lists is FOR, when the runner does not say. Docker and Ollama both read
     * this, and both promise it matches their own startup registration - one function, so the
     * promise cannot rot in one of the two copies.
     */
    @Nested
    inner class DecidingWhatALocalModelIsFor {

        @Test
        fun `a model some embedding role names is an embedding model`() {
            val properties = ConfigurableModelProviderProperties(
                defaultLlm = DEFAULT_LLM,
                embeddingRoles = mapOf(DOCUMENTS_ROLE to mapOf(DOCKER to PULLED_EMBEDDING)),
            )

            assertEquals(
                LocalModelKind.EMBEDDING,
                LocalModelKind.fromConfiguration(PULLED_EMBEDDING, properties),
            )
        }

        @Test
        fun `anything configuration does not name as an embedding model is a chat model`() {
            val properties = ConfigurableModelProviderProperties(defaultLlm = DEFAULT_LLM)

            assertEquals(LocalModelKind.CHAT, LocalModelKind.fromConfiguration(PULLED_LLM, properties))
        }
    }

    @Nested
    inner class StartingBeforeTheModelIsPulled {

        /**
         * An appliance whose default LLM is local: a role naming a model nobody has pulled yet is
         * the ordinary state before setup, and refusing to start would refuse to reach the point
         * where the operator could pull it.
         */
        private fun properties() = ConfigurableModelProviderProperties(
            defaultLlm = "ai/qwen3-boot",
            roles = mapOf(CHEAPEST_ROLE to mapOf(DOCKER to LlmOptions.withModel(PULLED_LLM))),
        )

        private val bootLlm: LlmService<*> =
            SpringAiLlmService("ai/qwen3-boot", DOCKER, mockk<ChatModel>(), DefaultOptionsConverter)

        @Test
        fun `a role naming an unpulled local model does not stop the deployment`() {
            val properties = properties()
            val runnerCatalog = catalog(FakeRunner())
            val mp = ConfigurableModelProvider(
                llms = listOf(bootLlm),
                embeddingServices = listOf(placeholder),
                properties = properties,
                roleResolvers = listOf(LocalModelRoleResolver(runnerCatalog, properties)),
                localModelCatalogs = listOf(runnerCatalog),
            )
            assertEquals("ai/qwen3-boot", mp.getLlm(DefaultModelSelectionCriteria).name)
        }

        /**
         * The excusal follows DISCOVERY, not the presence of a resolver bean. With discovery off
         * nothing will ever ask the runner, so the same entry can only ever be a typo and tolerating
         * it would leave the role permanently dead with no boot failure naming it - which is the
         * opposite of what "off restores the startup-only behaviour" promises.
         */
        @Test
        fun `the role is fatal again when local discovery is disabled`() {
            val properties = properties()
            val disabled = LocalModelDiscoveryProperties(enabled = false)
            val runnerCatalog = LocalModelCatalog(FakeRunner(), disabled, ticker)

            assertThrows<IllegalStateException> {
                ConfigurableModelProvider(
                    llms = listOf(bootLlm),
                    embeddingServices = listOf(placeholder),
                    properties = properties,
                    roleResolvers = listOf(LocalModelRoleResolver(runnerCatalog, properties)),
                    localModelCatalogs = listOf(runnerCatalog),
                )
            }
        }

        /**
         * The other half of the rule, so the excusal cannot be mistaken for the check having been
         * dropped: the same entry with nothing declaring the provider late-arriving is still a typo,
         * and still fatal.
         */
        @Test
        fun `the same role is still fatal when nothing declares the provider late-arriving`() {
            val properties = properties()
            assertThrows<IllegalStateException> {
                ConfigurableModelProvider(
                    llms = listOf(bootLlm),
                    embeddingServices = listOf(placeholder),
                    properties = properties,
                )
            }
        }
    }

    @Nested
    inner class OptionsConfiguredBesideTheModel {

        /**
         * A locally served model resolves to a built service rather than to a name, and a service
         * used to arrive with the role's options discarded - so `temperature: 0.0` beside the model
         * name was silently dropped by virtue of the model being local.
         */
        @Test
        fun `a temperature configured with the role survives resolution`() {
            val runner = FakeRunner(serving = chat(PULLED_LLM))
            val mp = llmProvider(
                runner,
                roleOptions = LlmOptions.withModel(PULLED_LLM).withTemperature(0.0),
            )

            val resolved = mp.resolveLlmOptions(LlmOptions.withLlmForRole(CHEAPEST_ROLE))

            assertEquals(0.0, resolved.temperature)
            assertEquals(CHEAPEST_ROLE, resolved.role)
        }
    }
}
