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
import com.embabel.agent.spi.PlaceholderLlmService
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.model.ModelProvider.Companion.CHEAPEST_ROLE
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ChatModel
import org.springframework.core.Ordered
import java.time.Duration

/**
 * Roles resolving against whichever provider is active, rather than against a fixed model name.
 */
class RoleResolutionTest {

    private fun llm(name: String, provider: String): LlmService<*> =
        SpringAiLlmService(name, provider, mockk<ChatModel>(), DefaultOptionsConverter)

    private val openAiModel = llm("gpt-4.1-nano", "openai")
    private val anthropicModel = llm("claude-haiku-4-5", "anthropic")
    private val defaultModel = llm("gpt-4.1-mini", "openai")

    /**
     * Stands in for `SetupRequiredLlm`, which lives in the BYOK module this one cannot depend on.
     * [SpringAiLlmService] is a data class and so final; the real placeholder carries the marker by
     * delegation in the same way.
     */
    private val placeholderModel: LlmService<*> = object :
        LlmService<SpringAiLlmService> by SpringAiLlmService(
            "setup-required", "none", mockk<ChatModel>(), DefaultOptionsConverter,
        ),
        PlaceholderLlmService {}

    /**
     * One role, two providers, each with its own model and its own tuning.
     */
    private val nestedRoles = mapOf(
        CHEAPEST_ROLE to mapOf(
            "openai" to LlmOptions.withModel("gpt-4.1-nano"),
            "anthropic" to LlmOptions.withModel("claude-haiku-4-5").withTemperature(0.3),
        ),
    )

    private fun provider(
        models: List<LlmService<*>> = listOf(openAiModel, anthropicModel, defaultModel),
        properties: ConfigurableModelProviderProperties = ConfigurableModelProviderProperties(
            roles = nestedRoles,
            defaultLlm = "gpt-4.1-mini",
        ),
        roleResolvers: List<RoleResolver> = emptyList(),
        factories: List<CredentialLlmServiceFactory> = emptyList(),
    ) = ConfigurableModelProvider(
        llms = models,
        embeddingServices = emptyList(),
        properties = properties,
        roleResolvers = roleResolvers,
        credentialLlmServiceFactories = factories,
    )

    @Nested
    inner class ProviderDimension {

        @Test
        fun `falls back to the provider of the default LLM when no key is active`() {
            val resolved = provider().getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE))
            assertEquals("gpt-4.1-nano", resolved.name)
        }

        @Test
        fun `resolves against the provider active for this call`() {
            val built = mutableListOf<String>()
            val mp = provider(
                factories = listOf(
                    CredentialLlmServiceFactory { _, model ->
                        built += model
                        anthropicModel
                    },
                ),
            )
            val resolved = ModelSelectionContextHolder.with(
                ModelSelectionContext(credential = ProviderCredential("anthropic", "sk-test")),
            ) {
                mp.resolveLlmOptions(LlmOptions.withLlmForRole(CHEAPEST_ROLE))
            }
            // The anthropic column, not the openai one the deployment default would have picked.
            assertEquals(listOf("claude-haiku-4-5"), built)
            assertEquals("claude-haiku-4-5", modelNameOf(resolved))
        }

        @Test
        fun `provider match is case insensitive`() {
            val mp = provider(
                properties = ConfigurableModelProviderProperties(
                    roles = mapOf(CHEAPEST_ROLE to mapOf("OpenAI" to LlmOptions.withModel("gpt-4.1-nano"))),
                    defaultLlm = "gpt-4.1-mini",
                ),
            )
            assertEquals("gpt-4.1-nano", mp.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE)).name)
        }

        @Test
        fun `flat llms map still works and is used when the nested shape says nothing`() {
            val mp = provider(
                properties = ConfigurableModelProviderProperties(
                    llms = mapOf(CHEAPEST_ROLE to "claude-haiku-4-5"),
                    defaultLlm = "gpt-4.1-mini",
                ),
            )
            assertEquals("claude-haiku-4-5", mp.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE)).name)
        }

        @Test
        fun `nested shape takes precedence over the flat map for the active provider`() {
            val mp = provider(
                properties = ConfigurableModelProviderProperties(
                    llms = mapOf(CHEAPEST_ROLE to "claude-haiku-4-5"),
                    roles = nestedRoles,
                    defaultLlm = "gpt-4.1-mini",
                ),
            )
            assertEquals("gpt-4.1-nano", mp.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE)).name)
        }
    }

    @Nested
    inner class UnsatisfiableRoles {

        @Test
        fun `a nested role naming an unregistered model for our own provider is fatal at startup`() {
            // Both shapes get the same rule, which is the point of routing them through one place:
            // an earlier revision made the nested shape warn while the flat map died, so the same
            // typo was fatal or silent depending on which shape you happened to write it in.
            val e = assertThrows<IllegalStateException> {
                provider(
                    models = listOf(defaultModel),
                    properties = ConfigurableModelProviderProperties(
                        roles = mapOf(CHEAPEST_ROLE to mapOf("openai" to LlmOptions.withModel("a-model-nobody-registered"))),
                        defaultLlm = "gpt-4.1-mini",
                    ),
                )
            }
            assertTrue(
                e.message!!.contains("a-model-nobody-registered") && e.message!!.contains(CHEAPEST_ROLE),
                "the failure must name the model and the role: ${e.message}",
            )
        }

        @Test
        fun `a role configured only for another provider throws`() {
            val mp = provider(
                models = listOf(defaultModel),
                properties = ConfigurableModelProviderProperties(
                    roles = mapOf(CHEAPEST_ROLE to mapOf("anthropic" to LlmOptions.withModel("claude-haiku-4-5"))),
                    defaultLlm = "gpt-4.1-mini",
                ),
            )
            assertThrows<NoSuitableModelException> {
                mp.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE))
            }
        }

        @Test
        fun `an unsatisfiable role never silently resolves to the default LLM`() {
            // "cheapest" quietly becoming the deployment's most capable model is the failure mode
            // this whole area exists to prevent. A keyed deployment now never even boots into that
            // state, so the guard is asserted at startup - and the message has to name the role,
            // since "some model is missing" is what gets fixed by adding a fallback.
            val expensiveDefault = llm("gpt-4.1-mini", "openai")
            val e = assertThrows<IllegalStateException> {
                provider(
                    models = listOf(expensiveDefault),
                    properties = ConfigurableModelProviderProperties(
                        roles = mapOf(CHEAPEST_ROLE to mapOf("openai" to LlmOptions.withModel("gpt-4.1-nano"))),
                        defaultLlm = "gpt-4.1-mini",
                    ),
                )
            }
            assertTrue(e.message!!.contains(CHEAPEST_ROLE), "the failure must name the role: ${e.message}")
        }

        @Test
        fun `constructing the provider DOES fail when a keyed deployment names an unavailable model`() {
            // A deployment whose default-llm resolves to a real model has a key, so a name nothing
            // registers is a typo. Booting anyway would move the failure to whichever unrelated
            // call first asked for the role.
            assertThrows<IllegalStateException> {
                provider(
                    models = listOf(defaultModel),
                    properties = ConfigurableModelProviderProperties(
                        llms = mapOf(CHEAPEST_ROLE to "a-model-nobody-registered"),
                        defaultLlm = "gpt-4.1-mini",
                    ),
                )
            }
        }

        @Test
        fun `a deployment awaiting a key starts, and its roles report that rather than failing`() {
            // The BYOK case: no key has arrived, so NO role can name a registered model. The
            // placeholder answers, so the caller gets the same actionable "no LLM configured" error
            // the default LLM already gives instead of a NoSuitableModelException.
            val mp = provider(
                models = listOf(placeholderModel),
                properties = ConfigurableModelProviderProperties(
                    llms = mapOf(CHEAPEST_ROLE to "gpt-4.1-nano"),
                    defaultLlm = "setup-required",
                ),
            )

            assertSame(placeholderModel, mp.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE)))
        }

        @Test
        fun `the placeholder is not handed out once a real default is registered`() {
            // The hybrid deployment - BYOK starter alongside a provider starter. It has a key, so
            // an unsatisfiable role is still an error rather than "you have not set a key".
            val mp = provider(
                models = listOf(defaultModel, placeholderModel),
                properties = ConfigurableModelProviderProperties(
                    roles = mapOf(CHEAPEST_ROLE to mapOf("anthropic" to LlmOptions.withModel("claude-haiku-4-5"))),
                    defaultLlm = "gpt-4.1-mini",
                ),
            )

            assertThrows<NoSuitableModelException> {
                mp.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE))
            }
        }

        @Test
        fun `a nested role awaiting a key warns instead of failing, like the flat map`() {
            // The other side of the gate for the nested shape. Both shapes route through
            // reportUnsatisfiableRole precisely so this half cannot drift apart from the flat
            // map's - keyed deployments die, deployments awaiting a key boot and report.
            val warnings = captureWarnings {
                provider(
                    models = listOf(placeholderModel),
                    properties = ConfigurableModelProviderProperties(
                        roles = mapOf(CHEAPEST_ROLE to mapOf("none" to LlmOptions.withModel("gpt-4.1-nanoo"))),
                        defaultLlm = "setup-required",
                    ),
                )
            }
            assertTrue(
                warnings.any { it.contains("gpt-4.1-nanoo") && it.contains(CHEAPEST_ROLE) },
                "the entry must still be reported, just not fatally: $warnings",
            )
        }

        @Test
        fun `a nested role for another provider is not warned about`() {
            // Its model is not expected to be registered here — that is the whole point of the
            // provider dimension. Warning would train people to ignore the warning.
            val warnings = captureWarnings {
                provider(
                    models = listOf(defaultModel),
                    properties = ConfigurableModelProviderProperties(
                        roles = mapOf(CHEAPEST_ROLE to mapOf("anthropic" to LlmOptions.withModel("claude-haiku-4-5"))),
                        defaultLlm = "gpt-4.1-mini",
                    ),
                )
            }
            assertTrue(
                warnings.none { it.contains("claude-haiku-4-5") },
                "a model for a provider we are not keyed for is not a misconfiguration: $warnings",
            )
        }

        @Test
        fun `a nested role naming no model at all is fatal too`() {
            // Tuning without a model cannot satisfy a role, so it is the same misconfiguration as
            // naming a model nothing registers and gets the same treatment.
            val e = assertThrows<IllegalStateException> {
                provider(
                    models = listOf(defaultModel),
                    properties = ConfigurableModelProviderProperties(
                        roles = mapOf(CHEAPEST_ROLE to mapOf("openai" to LlmOptions().withTemperature(0.3))),
                        defaultLlm = "gpt-4.1-mini",
                    ),
                )
            }
            assertTrue(e.message!!.contains("names no model"), e.message)
        }

        @Test
        fun `a role nobody configured still throws`() {
            assertThrows<NoSuitableModelException> {
                provider().getLlm(ByRoleModelSelectionCriteria("no-such-role"))
            }
        }
    }

    @Nested
    inner class RoleCarriedOptions {

        @Test
        fun `hyperparameters configured against a role are applied`() {
            val mp = provider(
                properties = ConfigurableModelProviderProperties(
                    roles = mapOf(
                        CHEAPEST_ROLE to mapOf(
                            "openai" to LlmOptions.withModel("gpt-4.1-nano")
                                .withTemperature(0.2)
                                .withTimeout(Duration.ofSeconds(90)),
                        ),
                    ),
                    defaultLlm = "gpt-4.1-mini",
                ),
            )
            val resolved = mp.resolveLlmOptions(LlmOptions.withLlmForRole(CHEAPEST_ROLE))
            assertEquals(0.2, resolved.temperature)
            assertEquals(Duration.ofSeconds(90), resolved.timeout)
        }

        @Test
        fun `what the caller set explicitly beats what the role configures`() {
            val mp = provider(
                properties = ConfigurableModelProviderProperties(
                    roles = mapOf(
                        CHEAPEST_ROLE to mapOf(
                            "openai" to LlmOptions.withModel("gpt-4.1-nano").withTemperature(0.2),
                        ),
                    ),
                    defaultLlm = "gpt-4.1-mini",
                ),
            )
            val resolved = mp.resolveLlmOptions(LlmOptions.withLlmForRole(CHEAPEST_ROLE).withTemperature(0.9))
            assertEquals(0.9, resolved.temperature)
        }

        @Test
        fun `options naming no role are returned untouched`() {
            val asked = LlmOptions.withModel("gpt-4.1-mini").withTemperature(0.5)
            assertEquals(asked, provider().resolveLlmOptions(asked))
        }

        @Test
        fun `resolved options still say which role was asked for`() {
            // Selection no longer consults it, but events, logs and cost attribution all want to
            // know the call was made "as cheapest" rather than as a bare model name.
            val resolved = provider().resolveLlmOptions(LlmOptions.withLlmForRole(CHEAPEST_ROLE))
            assertEquals(CHEAPEST_ROLE, resolved.role)
        }

        @Test
        fun `resolved options carry the chosen service so it is not looked up twice`() {
            val resolved = provider().resolveLlmOptions(LlmOptions.withLlmForRole(CHEAPEST_ROLE))
            val criteria = resolved.criteria
            assertTrue(criteria is PreResolvedModelSelectionCriteria<*>)
            assertSame(openAiModel, (criteria as PreResolvedModelSelectionCriteria<*>).resolved)
        }
    }

    @Nested
    inner class ApplicationResolvers {

        @Test
        fun `an application resolver wins over configuration`() {
            val mp = provider(
                roleResolvers = listOf(
                    RoleResolver { _, _ -> RoleResolution.Service(anthropicModel) },
                ),
            )
            assertSame(anthropicModel, mp.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE)))
        }

        @Test
        fun `a resolver returning null delegates to the next one`() {
            val mp = provider(
                roleResolvers = listOf(
                    RoleResolver { _, _ -> null },
                    RoleResolver { _, _ -> RoleResolution.Service(anthropicModel) },
                ),
            )
            assertSame(anthropicModel, mp.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE)))
        }

        @Test
        fun `a resolver sees the user and key active for the call`() {
            var seen: ModelSelectionContext? = null
            val mp = provider(
                roleResolvers = listOf(
                    RoleResolver { _, context ->
                        seen = context
                        RoleResolution.Service(anthropicModel)
                    },
                ),
            )
            val context = ModelSelectionContext("ben", ProviderCredential("anthropic", "sk-test"))
            ModelSelectionContextHolder.with(context) {
                mp.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE))
            }
            assertEquals(context, seen)
        }
    }

    @Nested
    inner class BringYourOwnKey {

        private val userService = llm("claude-haiku-4-5", "anthropic")
        private var buildCount = 0

        private val factory = CredentialLlmServiceFactory { credential, model ->
            if (credential.provider == "anthropic" && model == "claude-haiku-4-5") {
                buildCount++
                userService
            } else {
                null
            }
        }

        @Test
        fun `a key in context resolves the role through that provider`() {
            val mp = provider(factories = listOf(factory))
            val resolved = ModelSelectionContextHolder.with(
                ModelSelectionContext("ben", ProviderCredential("anthropic", "sk-test")),
            ) {
                mp.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE))
            }
            assertSame(userService, resolved)
        }

        @Test
        fun `the role's tuning for that provider travels with the user's key`() {
            val mp = provider(factories = listOf(factory))
            val resolved = ModelSelectionContextHolder.with(
                ModelSelectionContext("ben", ProviderCredential("anthropic", "sk-test")),
            ) {
                mp.resolveLlmOptions(LlmOptions.withLlmForRole(CHEAPEST_ROLE))
            }
            assertEquals(0.3, resolved.temperature)
        }

        @Test
        fun `a service built from a key is reused rather than rebuilt per call`() {
            val mp = provider(factories = listOf(factory))
            buildCount = 0
            repeat(3) {
                ModelSelectionContextHolder.with(
                    ModelSelectionContext("ben", ProviderCredential("anthropic", "sk-test")),
                ) {
                    mp.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE))
                }
            }
            assertEquals(1, buildCount)
        }

        @Test
        fun `two users with different keys do not share a service`() {
            val built = mutableListOf<ProviderCredential>()
            val perKeyFactory = CredentialLlmServiceFactory { credential, _ ->
                built += credential
                llm("claude-haiku-4-5", "anthropic")
            }
            val mp = provider(factories = listOf(perKeyFactory))
            listOf("sk-ben", "sk-rod").forEach { key ->
                ModelSelectionContextHolder.with(
                    ModelSelectionContext(credential = ProviderCredential("anthropic", key)),
                ) {
                    mp.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE))
                }
            }
            assertEquals(listOf("sk-ben", "sk-rod"), built.map { it.apiKey })
        }

        @Test
        fun `a user key is never served from deployment credentials`() {
            // The role has no mistral column, and the deployment's own models are keyed by the
            // deployment. Serving them here would bill the deployment for this user's call.
            val mp = provider(factories = listOf(factory))
            assertThrows<NoSuitableModelException> {
                ModelSelectionContextHolder.with(
                    ModelSelectionContext(credential = ProviderCredential("mistral", "sk-test")),
                ) {
                    mp.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE))
                }
            }
        }

        @Test
        fun `a user key does not fall through to the flat llms map either`() {
            val mp = provider(
                properties = ConfigurableModelProviderProperties(
                    llms = mapOf(CHEAPEST_ROLE to "gpt-4.1-nano"),
                    defaultLlm = "gpt-4.1-mini",
                ),
                factories = listOf(factory),
            )
            assertThrows<NoSuitableModelException> {
                ModelSelectionContextHolder.with(
                    ModelSelectionContext(credential = ProviderCredential("anthropic", "sk-test")),
                ) {
                    mp.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE))
                }
            }
        }

        @Test
        fun `the same key and model reuse one service, a different key does not`() {
            // The cache is keyed by a digest of the key rather than the key itself. That must not
            // change what the cache MEANS: same key reuses, different key rebuilds.
            val built = mutableListOf<String>()
            val perKeyFactory = CredentialLlmServiceFactory { credential, _ ->
                built += credential.apiKey
                llm("claude-haiku-4-5", "anthropic")
            }
            val mp = provider(factories = listOf(perKeyFactory))
            listOf("sk-ben", "sk-ben", "sk-rod", "sk-ben").forEach { key ->
                ModelSelectionContextHolder.with(
                    ModelSelectionContext(credential = ProviderCredential("anthropic", key)),
                ) {
                    mp.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE))
                }
            }
            assertEquals(listOf("sk-ben", "sk-rod"), built)
        }

        @Test
        fun `the cache is bounded by configuration, and evicted entries rebuild`() {
            val built = mutableListOf<String>()
            val mp = provider(
                properties = ConfigurableModelProviderProperties(
                    roles = nestedRoles,
                    defaultLlm = "gpt-4.1-mini",
                    credentialServiceCacheSize = 2,
                ),
                factories = listOf(
                    CredentialLlmServiceFactory { credential, _ ->
                        built += credential.apiKey
                        llm("claude-haiku-4-5", "anthropic")
                    },
                ),
            )
            fun resolveFor(key: String) = ModelSelectionContextHolder.with(
                ModelSelectionContext(credential = ProviderCredential("anthropic", key)),
            ) {
                mp.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE))
            }

            listOf("sk-a", "sk-b", "sk-c").forEach { resolveFor(it) }
            resolveFor("sk-a")

            // sk-a was the least recently used when sk-c arrived, so it is gone and rebuilds.
            // Exceeding the bound costs construction, never correctness.
            assertEquals(listOf("sk-a", "sk-b", "sk-c", "sk-a"), built)
        }

        @Test
        fun `a cache bound below one is rejected at startup rather than silently disabling the cache`() {
            // Zero evicts every entry as soon as it is inserted, so nothing is ever cached and
            // every call rebuilds a service over the network. That is indistinguishable from a
            // caching bug and only visible as latency, so it fails here instead.
            listOf(0, -1).forEach { size ->
                val ex = assertThrows<IllegalArgumentException> {
                    provider(
                        properties = ConfigurableModelProviderProperties(
                            roles = nestedRoles,
                            defaultLlm = "gpt-4.1-mini",
                            credentialServiceCacheSize = size,
                        ),
                        factories = listOf(factory),
                    )
                }
                assertTrue(
                    ex.message!!.contains("credential-service-cache-size"),
                    "the message must name the property an operator would set, was: ${ex.message}",
                )
            }
        }

        @Test
        fun `a provider no factory handles fails rather than falling back`() {
            // The role names a model for openai, but the only factory speaks anthropic. Serving
            // the deployment's own openai model here would bill the deployment for the user's call.
            val mp = provider(factories = listOf(factory))
            assertThrows<NoSuitableModelException> {
                ModelSelectionContextHolder.with(
                    ModelSelectionContext(credential = ProviderCredential("openai", "sk-test")),
                ) {
                    mp.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE))
                }
            }
        }

        @Test
        fun `a role column with tuning but no model cannot be satisfied by a key`() {
            val mp = provider(
                properties = ConfigurableModelProviderProperties(
                    roles = mapOf(CHEAPEST_ROLE to mapOf("anthropic" to LlmOptions().withTemperature(0.3))),
                    defaultLlm = "gpt-4.1-mini",
                ),
                factories = listOf(factory),
            )
            assertThrows<NoSuitableModelException> {
                ModelSelectionContextHolder.with(
                    ModelSelectionContext(credential = ProviderCredential("anthropic", "sk-test")),
                ) {
                    mp.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE))
                }
            }
        }
    }

    @Nested
    inner class PlatformResolver {

        private val resolver = ConfigurableRoleResolver(
            ConfigurableModelProviderProperties(roles = nestedRoles, defaultLlm = "gpt-4.1-mini"),
        ) { "openai" }

        @Test
        fun `runs last, so an application resolver can always take precedence`() {
            assertEquals(Ordered.LOWEST_PRECEDENCE, resolver.order)
        }

        @Test
        fun `says nothing when there is no provider to resolve against`() {
            assertNull(resolver.optionsFor(CHEAPEST_ROLE, provider = null))
        }

        @Test
        fun `the configured view answers with the nested entry for the provider`() {
            assertEquals("gpt-4.1-nano", provider().configuredOptionsForRole(CHEAPEST_ROLE)?.modelName)
        }

        @Test
        fun `the configured view falls back to the flat map`() {
            val mp = provider(
                properties = ConfigurableModelProviderProperties(
                    llms = mapOf(CHEAPEST_ROLE to "claude-haiku-4-5"),
                    defaultLlm = "gpt-4.1-mini",
                ),
            )
            assertEquals("claude-haiku-4-5", mp.configuredOptionsForRole(CHEAPEST_ROLE)?.modelName)
        }

        @Test
        fun `the configured view agrees with what resolution actually picks`() {
            // The reason this API exists: a settings screen that reads config itself drifts from
            // what runs. Both shapes configured, nested wins — and both answers must say so.
            val mp = provider(
                properties = ConfigurableModelProviderProperties(
                    llms = mapOf(CHEAPEST_ROLE to "claude-haiku-4-5"),
                    roles = nestedRoles,
                    defaultLlm = "gpt-4.1-mini",
                ),
            )
            assertEquals(
                mp.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE)).name,
                mp.configuredOptionsForRole(CHEAPEST_ROLE)?.modelName,
            )
        }

        @Test
        fun `the configured view ignores application resolvers, which are per-call`() {
            val mp = provider(
                roleResolvers = listOf(RoleResolver { _, _ -> RoleResolution.Service(anthropicModel) }),
            )
            // The resolver decides what RUNS; configuration is what the deployment DECLARES.
            assertEquals("gpt-4.1-nano", mp.configuredOptionsForRole(CHEAPEST_ROLE)?.modelName)
        }

        @Test
        fun `the configured view says nothing about an unconfigured role`() {
            assertNull(provider().configuredOptionsForRole("no-such-role"))
        }

        @Test
        fun `says nothing about a role it has never heard of`() {
            assertNull(resolver.optionsFor("no-such-role", provider = "openai"))
            assertNull(resolver.resolve("no-such-role", ModelSelectionContext.EMPTY))
        }
    }

    @Nested
    inner class DefaultSpiBehaviour {

        /**
         * A [ModelProvider] that does not override role resolution: the default is to leave
         * options alone, so an implementation predating roles keeps working.
         */
        private val minimal = object : ModelProvider {
            override fun getLlm(criteria: ModelSelectionCriteria): LlmService<*> = openAiModel
            override fun getEmbeddingService(criteria: ModelSelectionCriteria): EmbeddingService =
                throw UnsupportedOperationException()

            override fun listRoles(modelClass: Class<*>): List<String> = emptyList()
            override fun listModels(): List<ModelMetadata> = emptyList()
            override fun listModelNames(modelClass: Class<*>): List<String> = emptyList()
            override fun infoString(verbose: Boolean?, indent: Int): String = "minimal"
        }

        @Test
        fun `the default resolveLlmOptions returns what it was given`() {
            val asked = LlmOptions.withLlmForRole(CHEAPEST_ROLE)
            assertSame(asked, minimal.resolveLlmOptions(asked))
        }

        @Test
        fun `the default configuredOptionsForRole says it cannot say`() {
            assertNull(minimal.configuredOptionsForRole(CHEAPEST_ROLE))
        }
    }

    @Nested
    inner class ContextHolder {

        @Test
        fun `defaults to empty`() {
            assertEquals(ModelSelectionContext.EMPTY, ModelSelectionContextHolder.get())
            assertNull(ModelSelectionContextHolder.get().provider)
        }

        @Test
        fun `restores the previous context when nested`() {
            val outer = ModelSelectionContext("outer")
            val inner = ModelSelectionContext("inner")
            ModelSelectionContextHolder.with(outer) {
                ModelSelectionContextHolder.with(inner) {
                    assertEquals(inner, ModelSelectionContextHolder.get())
                }
                assertEquals(outer, ModelSelectionContextHolder.get())
            }
            assertEquals(ModelSelectionContext.EMPTY, ModelSelectionContextHolder.get())
        }

        @Test
        fun `restores the previous context even when the body throws`() {
            assertThrows<IllegalStateException> {
                ModelSelectionContextHolder.with(ModelSelectionContext("ben")) {
                    error("boom")
                }
            }
            assertEquals(ModelSelectionContext.EMPTY, ModelSelectionContextHolder.get())
        }

        @Test
        fun `a credential never prints its key`() {
            val rendered = ProviderCredential("anthropic", "sk-very-secret").toString()
            assertTrue(rendered.contains("anthropic"))
            assertTrue(!rendered.contains("sk-very-secret"))
        }
    }

    /**
     * Warnings emitted by [ConfigurableModelProvider] while [block] runs. Startup diagnostics are
     * the whole product of the checks above, so asserting on them is asserting on the behaviour.
     */
    private fun captureWarnings(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(ConfigurableModelProvider::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
    }

    private fun modelNameOf(options: LlmOptions): String =
        (options.criteria as PreResolvedModelSelectionCriteria<*>).resolved
            .let { it as LlmService<*> }.name
}
