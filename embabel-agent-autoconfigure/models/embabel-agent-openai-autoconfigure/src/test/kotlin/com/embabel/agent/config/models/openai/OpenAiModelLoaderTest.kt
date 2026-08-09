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
package com.embabel.agent.config.models.openai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.ResourceLoader
import java.time.LocalDate

class OpenAiModelLoaderTest {

    /**
     * The catalogue we actually ship, in `src/main/resources/models/openai-models.yml`.
     *
     * Every assertion here starts by proving the catalogue is non-empty. The loader answers a
     * missing or unparseable file with an *empty* provider rather than an exception, so a test
     * that iterates the catalogue without that guard stays green after the file is deleted,
     * renamed, or broken — it asserts nothing at all.
     */
    @Nested
    inner class ShippedCatalogue {

        @Test
        fun `loads model definitions with non-blank names and ids`() {
            assertTrue(shippedCatalogue.models.isNotEmpty(), "Should load at least one model")

            shippedCatalogue.models.forEach { model ->
                assertTrue(model.name.isNotBlank(), "Model name should not be blank")
                assertTrue(model.modelId.isNotBlank(), "Model ID should not be blank for ${model.name}")
            }
        }

        @Test
        fun `every model carries values inside the ranges the loader validates`() {
            assertTrue(shippedCatalogue.models.isNotEmpty(), "Guard against asserting over an empty catalogue")

            shippedCatalogue.models.forEach { model ->
                assertTrue(model.maxTokens > 0, "Max tokens should be positive for ${model.name}")
                assertTrue(model.temperature in 0.0..2.0, "Temperature should be in valid range for ${model.name}")
                model.topP?.let {
                    assertTrue(it in 0.0..1.0, "Top P should be between 0 and 1 for ${model.name}")
                }
            }
        }

        @Test
        fun `known models are present and priced`() {
            val modelNames = shippedCatalogue.models.map { it.name }
            assertTrue(modelNames.contains("gpt53chat"), "Should include GPT-5.3 Chat")

            assertTrue(
                shippedCatalogue.models.any { it.pricingModel != null },
                "At least one model should have pricing information",
            )
        }

        /**
         * The whole GPT-5 line rejects sampling parameters and `max_tokens`. Catalog flags drive
         * [com.embabel.agent.openai.CapabilityAwareOpenAiOptionsConverter] / the GPT-5 alias
         * (warn-and-drop, never throw). A model shipped without these sends fields OpenAI rejects.
         */
        @Test
        fun `every GPT-5 model declares sampling and max_completion_tokens capabilities`() {
            val gpt5Models = shippedCatalogue.models.filter { it.name.startsWith("gpt5") }
            assertTrue(gpt5Models.isNotEmpty(), "Should have GPT-5 models")

            gpt5Models.forEach { model ->
                assertNotNull(model.specialHandling, "GPT-5 models should have special handling")
                assertEquals(
                    false,
                    model.specialHandling?.supportsTemperature,
                    "GPT-5 models should not support temperature adjustment (${model.modelId})",
                )
                assertEquals(
                    false,
                    model.specialHandling?.supportsTopP,
                    "GPT-5 models should not support top_p (${model.modelId})",
                )
                assertEquals(
                    false,
                    model.specialHandling?.supportsFrequencyPenalty,
                    "GPT-5 models should not support frequency_penalty (${model.modelId})",
                )
                assertEquals(
                    false,
                    model.specialHandling?.supportsPresencePenalty,
                    "GPT-5 models should not support presence_penalty (${model.modelId})",
                )
                assertEquals(
                    true,
                    model.specialHandling?.usesMaxCompletionTokens,
                    "GPT-5 models map the token limit to max_completion_tokens (${model.modelId})",
                )
            }
        }

        /**
         * GPT-4.1 family is temperature-restricted but still accepts classic `max_tokens` and
         * other sampling fields (unlike the GPT-5 family).
         */
        @Test
        fun `every GPT-4_1 model declares temperature-only special handling`() {
            val gpt41Models = shippedCatalogue.models.filter { it.name.startsWith("gpt41") }
            assertTrue(gpt41Models.isNotEmpty(), "Should have GPT-4.1 models")

            gpt41Models.forEach { model ->
                assertNotNull(model.specialHandling, "GPT-4.1 models should have special handling")
                assertEquals(
                    false,
                    model.specialHandling?.supportsTemperature,
                    "GPT-4.1 models should not support temperature adjustment (${model.modelId})",
                )
                assertEquals(
                    true,
                    model.specialHandling?.supportsTopP ?: true,
                    "GPT-4.1 still accepts top_p (${model.modelId})",
                )
                assertEquals(
                    false,
                    model.specialHandling?.usesMaxCompletionTokens ?: false,
                    "GPT-4.1 still uses max_tokens (${model.modelId})",
                )
            }
        }

        @Test
        fun `embedding models are loaded with positive dimensions`() {
            assertTrue(shippedCatalogue.embeddingModels.isNotEmpty(), "Should load at least one embedding model")

            shippedCatalogue.embeddingModels.forEach { embedding ->
                assertTrue(embedding.name.isNotBlank(), "Embedding name should not be blank")
                assertTrue(embedding.modelId.isNotBlank(), "Embedding model ID should not be blank")
                val dimensions = assertPresent(
                    embedding.dimensions,
                    "Dimensions should not be null for ${embedding.name}",
                )
                assertTrue(dimensions > 0, "Dimensions should be positive for ${embedding.name}")
            }
        }

        @Test
        fun `embedding pricing is non-negative`() {
            assertTrue(shippedCatalogue.embeddingModels.isNotEmpty(), "Guard against asserting over an empty list")

            shippedCatalogue.embeddingModels.forEach { embedding ->
                embedding.pricingModel?.let { pricing ->
                    assertTrue(
                        pricing.usdPer1mTokens >= 0.0,
                        "Pricing should be non-negative for ${embedding.name}",
                    )
                }
            }
        }
    }

    @Nested
    inner class ModelParsing {

        @Test
        fun `loads a model with all optional fields`() {
            val result = loaderFor(
                """
                models:
                  - name: test-model
                    model_id: gpt-test
                    display_name: Test Model
                    knowledge_cutoff_date: 2026-01-31
                    max_tokens: 4096
                    temperature: 0.7
                    top_p: 0.9
                    special_handling:
                      supports_temperature: false
                    pricing_model:
                      usd_per1m_input_tokens: 10.0
                      usd_per1m_output_tokens: 20.0
                """.trimIndent()
            ).loadAutoConfigMetadata()

            val model = assertSingleModel(result)
            assertEquals("test-model", model.name)
            assertEquals("gpt-test", model.modelId)
            assertEquals("Test Model", model.displayName)
            assertEquals(LocalDate.of(2026, 1, 31), model.knowledgeCutoffDate)
            assertEquals(4096, model.maxTokens)
            assertEquals(0.7, model.temperature)
            assertEquals(0.9, model.topP)
            assertEquals(false, model.specialHandling?.supportsTemperature)
            assertEquals(10.0, model.pricingModel?.usdPer1mInputTokens)
            assertEquals(20.0, model.pricingModel?.usdPer1mOutputTokens)
        }

        @Test
        fun `applies documented defaults to a model with minimal fields`() {
            val result = loaderFor(
                """
                models:
                  - name: minimal-model
                    model_id: gpt-minimal
                """.trimIndent()
            ).loadAutoConfigMetadata()

            val model = assertSingleModel(result)
            assertEquals("minimal-model", model.name)
            assertEquals("gpt-minimal", model.modelId)
            assertNull(model.displayName)
            assertNull(model.knowledgeCutoffDate)
            assertEquals(16384, model.maxTokens) // Default value
            assertEquals(1.0, model.temperature) // Default value
            assertNull(model.topP)
            assertNull(model.specialHandling)
            assertNull(model.pricingModel)
        }

        @Test
        fun `loads multiple models in declaration order`() {
            val result = loaderFor(
                """
                models:
                  - name: model-1
                    model_id: gpt-1
                    max_tokens: 2000
                  - name: model-2
                    model_id: gpt-2
                    max_tokens: 4000
                """.trimIndent()
            ).loadAutoConfigMetadata()

            assertEquals(listOf("model-1", "model-2"), result.models.map { it.name })
            assertEquals(listOf(2000, 4000), result.models.map { it.maxTokens })
        }

        @Test
        fun `loads both LLM and embedding models from the same file`() {
            val result = loaderFor(
                """
                models:
                  - name: llm-1
                    model_id: gpt-test-1
                    max_tokens: 4096
                  - name: llm-2
                    model_id: gpt-test-2
                    max_tokens: 8192
                embedding_models:
                  - name: embedding-1
                    model_id: text-embedding-1
                    dimensions: 1536
                  - name: embedding-2
                    model_id: text-embedding-2
                    dimensions: 3072
                """.trimIndent()
            ).loadAutoConfigMetadata()

            assertEquals(2, result.models.size, "Should load 2 LLM models")
            assertEquals(2, result.embeddingModels.size, "Should load 2 embedding models")
        }

        @Test
        fun `an empty model list is not an error`() {
            val result = loaderFor("models: []").loadAutoConfigMetadata()

            assertTrue(result.models.isEmpty())
            assertTrue(result.embeddingModels.isEmpty())
        }
    }

    @Nested
    inner class EmbeddingModelParsing {

        @Test
        fun `loads an embedding model with all fields`() {
            val result = loaderFor(
                """
                embedding_models:
                  - name: test-embedding
                    model_id: text-embedding-test
                    display_name: Test Embedding
                    dimensions: 1536
                    pricing_model:
                      usd_per1m_tokens: 0.02
                """.trimIndent()
            ).loadAutoConfigMetadata()

            assertEquals(1, result.embeddingModels.size)
            val embedding = result.embeddingModels.first()
            assertEquals("test-embedding", embedding.name)
            assertEquals("text-embedding-test", embedding.modelId)
            assertEquals("Test Embedding", embedding.displayName)
            assertEquals(1536, embedding.dimensions)
            assertEquals(0.02, embedding.pricingModel?.usdPer1mTokens)
        }

        @Test
        fun `loads multiple embedding models in declaration order`() {
            val result = loaderFor(
                """
                embedding_models:
                  - name: embedding-1
                    model_id: text-embedding-1
                    dimensions: 1536
                  - name: embedding-2
                    model_id: text-embedding-2
                    dimensions: 3072
                """.trimIndent()
            ).loadAutoConfigMetadata()

            assertEquals(listOf("embedding-1", "embedding-2"), result.embeddingModels.map { it.name })
            assertEquals(listOf(1536, 3072), result.embeddingModels.map { it.dimensions })
        }
    }

    /**
     * Validation is all-or-nothing and *silent*: [OpenAiModelLoader.validateModels] throws on the
     * first offending entry, and the loader answers by logging and returning an empty provider.
     * One bad line in a user override therefore costs every model in the file, not just its own —
     * so each case below is asserted against a file that also contains a valid model, otherwise
     * `models.isEmpty()` would prove nothing about the blast radius.
     */
    @Nested
    inner class Validation {

        @Test
        fun `one invalid model discards every model in the file`() {
            val result = loaderFor(
                """
                models:
                  - name: perfectly-good-model
                    model_id: gpt-good
                  - name: bad-model
                    model_id: gpt-bad
                    max_tokens: -100
                """.trimIndent()
            ).loadAutoConfigMetadata()

            assertTrue(
                result.models.isEmpty(),
                "A single invalid model must not leave a partially loaded catalogue behind",
            )
        }

        @Test
        fun `one invalid embedding model also discards the LLM models`() {
            val result = loaderFor(
                """
                models:
                  - name: perfectly-good-model
                    model_id: gpt-good
                embedding_models:
                  - name: bad-embedding
                    model_id: text-embedding-bad
                    dimensions: -100
                """.trimIndent()
            ).loadAutoConfigMetadata()

            assertTrue(result.models.isEmpty(), "Validation failure is file-wide, not per-section")
            assertTrue(result.embeddingModels.isEmpty())
        }

        @Test
        fun `rejects negative maxTokens`() {
            assertModelRejected(
                """
                - name: test-model
                  model_id: gpt-test
                  max_tokens: -100
                """.trimIndent()
            )
        }

        @Test
        fun `rejects temperature above the supported range`() {
            assertModelRejected(
                """
                - name: test-model
                  model_id: gpt-test
                  temperature: 3.0
                """.trimIndent()
            )
        }

        @Test
        fun `rejects topP above the supported range`() {
            assertModelRejected(
                """
                - name: test-model
                  model_id: gpt-test
                  top_p: 1.5
                """.trimIndent()
            )
        }

        @Test
        fun `rejects a blank model name`() {
            assertModelRejected(
                """
                - name: ""
                  model_id: gpt-test
                """.trimIndent()
            )
        }

        @Test
        fun `rejects a blank model id`() {
            assertModelRejected(
                """
                - name: test-model
                  model_id: ""
                """.trimIndent()
            )
        }

        /**
         * Negative pricing is caught for embedding models, so it must be caught here too:
         * a negative rate silently turns cost accounting into a credit.
         */
        @Test
        fun `rejects negative LLM input pricing`() {
            assertModelRejected(
                """
                - name: test-model
                  model_id: gpt-test
                  pricing_model:
                    usd_per1m_input_tokens: -5.0
                    usd_per1m_output_tokens: 20.0
                """.trimIndent()
            )
        }

        @Test
        fun `rejects negative LLM output pricing`() {
            assertModelRejected(
                """
                - name: test-model
                  model_id: gpt-test
                  pricing_model:
                    usd_per1m_input_tokens: 5.0
                    usd_per1m_output_tokens: -20.0
                """.trimIndent()
            )
        }

        @Test
        fun `rejects negative embedding dimensions`() {
            assertEmbeddingRejected(
                """
                - name: test-embedding
                  model_id: text-embedding-test
                  dimensions: -100
                """.trimIndent()
            )
        }

        @Test
        fun `rejects a blank embedding name`() {
            assertEmbeddingRejected(
                """
                - name: ""
                  model_id: text-embedding-test
                  dimensions: 1536
                """.trimIndent()
            )
        }

        @Test
        fun `rejects negative embedding pricing`() {
            assertEmbeddingRejected(
                """
                - name: test-embedding
                  model_id: text-embedding-test
                  dimensions: 1536
                  pricing_model:
                    usd_per1m_tokens: -0.5
                """.trimIndent()
            )
        }

        private fun assertModelRejected(invalidEntry: String) {
            assertRejected(CANARY_MODEL + "\n" + invalidEntry.prependIndent("  "))
        }

        private fun assertEmbeddingRejected(invalidEntry: String) {
            assertRejected(CANARY_MODEL + "\nembedding_models:\n" + invalidEntry.prependIndent("  "))
        }

        /**
         * The offending file always also declares a valid model, so an empty result proves the
         * loader rejected the file rather than merely failing to see the invalid entry.
         */
        private fun assertRejected(yaml: String) {
            val result = loaderFor(yaml).loadAutoConfigMetadata()

            assertTrue(
                result.models.isEmpty() && result.embeddingModels.isEmpty(),
                "Invalid definition should be rejected, dropping the canary model with it:\n$yaml",
            )
        }
    }

    @Nested
    inner class LoadFailures {

        @Test
        fun `returns empty definitions when the file does not exist`() {
            val result = OpenAiModelLoader(
                resourceLoader = DefaultResourceLoader(),
                configPath = "classpath:nonexistent-file.yml",
            ).loadAutoConfigMetadata()

            assertTrue(result.models.isEmpty(), "Should return empty list when file not found")
            assertTrue(result.embeddingModels.isEmpty(), "Should return empty embedding list when file not found")
        }

        @Test
        fun `returns empty definitions on a YAML parse error`() {
            val result = loaderFor("invalid: yaml: content: ][").loadAutoConfigMetadata()

            assertTrue(result.models.isEmpty(), "Should return empty list on parse error")
            assertTrue(result.embeddingModels.isEmpty(), "Should return empty embedding list on parse error")
        }
    }

    @Nested
    inner class NativeSupportTests {

        @Test
        fun `default YAML loads native structured-output metadata for chat models`() {
            val structuredOutput = assertPresent(
                shippedCatalogue.nativeSupportDefaults?.structuredOutput,
                "The shipped catalogue declares native_support_defaults",
            )
            assertEquals("response_format", structuredOutput.strategy)
            assertEquals(false, structuredOutput.strict)
            assertEquals("include", structuredOutput.promptInstructions)
            assertEquals("response_format", structuredOutput.options["response_format_field"])
            assertEquals("json_schema", structuredOutput.options["type_value"])
            assertEquals("json_schema", structuredOutput.options["json_schema_field"])

            val effectiveModel = shippedCatalogue.effectiveModels().first { it.name == "gpt54" }
            assertEquals(
                "response_format",
                effectiveModel.nativeSupport?.structuredOutput?.strategy,
                "A model declaring no native_support of its own inherits the defaults",
            )
        }

        /**
         * The interesting half of [NativeStructuredOutputCapability.merge] is the *partial*
         * override: unset fields fall back to the defaults and `options` is a union, not a
         * replacement. A model that restates every default proves none of that, so this fixture
         * deliberately sets only `strict` and a single option key.
         */
        @Test
        fun `a partial model override inherits unset fields and unions the options`() {
            val model = loaderFor(
                """
                native_support_defaults:
                  structured_output:
                    supported: true
                    strategy: response_format
                    strict: true
                    prompt_instructions: include
                    options:
                      response_format_field: response_format
                      json_schema_field: json_schema
                models:
                  - name: alias-model
                    model_id: gpt-alias
                    native_support:
                      structured_output:
                        strict: false
                        options:
                          json_schema_field: custom_json_schema
                """.trimIndent()
            ).loadAutoConfigMetadata().effectiveModels().single()

            val structuredOutput = assertPresent(
                model.nativeSupport?.structuredOutput,
                "Native structured-output metadata should be present",
            )
            assertEquals(false, structuredOutput.strict, "The model's own value wins")
            assertEquals(true, structuredOutput.supported, "Unset fields fall back to the defaults")
            assertEquals("response_format", structuredOutput.strategy)
            assertEquals("include", structuredOutput.promptInstructions)
            assertEquals(
                mapOf(
                    "response_format_field" to "response_format",
                    "json_schema_field" to "custom_json_schema",
                ),
                structuredOutput.options,
                "Options merge key by key: the default key survives, the overridden key changes",
            )
        }

        @Test
        fun `a fully specified model override replaces every default`() {
            val model = loaderFor(
                """
                native_support_defaults:
                  structured_output:
                    supported: true
                    strategy: response_format
                    strict: true
                    prompt_instructions: include
                    options:
                      response_format_field: response_format
                      json_schema_field: json_schema
                models:
                  - name: alias-model
                    model_id: gpt-alias
                    native_support:
                      structured_output:
                        supported: false
                        strategy: tool
                        strict: false
                        prompt_instructions: suppress
                        options:
                          response_format_field: custom_response_format
                          json_schema_field: custom_json_schema
                """.trimIndent()
            ).loadAutoConfigMetadata().effectiveModels().single()

            val structuredOutput = assertPresent(
                model.nativeSupport?.structuredOutput,
                "Native structured-output metadata should be present",
            )
            assertEquals(false, structuredOutput.supported)
            assertEquals("tool", structuredOutput.strategy)
            assertEquals(false, structuredOutput.strict)
            assertEquals("suppress", structuredOutput.promptInstructions)
            assertEquals("custom_response_format", structuredOutput.options["response_format_field"])
            assertEquals("custom_json_schema", structuredOutput.options["json_schema_field"])
        }

        @Test
        fun `effectiveModels leaves native support null when the file declares no defaults`() {
            val model = loaderFor(
                """
                models:
                  - name: plain-model
                    model_id: gpt-plain
                """.trimIndent()
            ).loadAutoConfigMetadata().effectiveModels().single()

            assertNull(model.nativeSupport)
        }

        /**
         * The kebab-case spellings are wired through [com.fasterxml.jackson.annotation.JsonAlias]
         * rather than the snake_case naming strategy, so they need their own coverage.
         */
        @Test
        fun `kebab-case native-support aliases are accepted`() {
            val model = loaderFor(
                """
                models:
                  - name: alias-model
                    model_id: gpt-alias
                    native-support:
                      structured-output:
                        strategy: response_format
                        prompt-instructions: suppress
                """.trimIndent()
            ).loadAutoConfigMetadata().models.single()

            val structuredOutput = assertPresent(
                model.nativeSupport?.structuredOutput,
                "Native structured-output metadata should be present",
            )
            assertEquals("response_format", structuredOutput.strategy)
            assertEquals("suppress", structuredOutput.promptInstructions)
        }
    }

    /**
     * The transport a model is served over, declared per model rather than inferred from its id.
     *
     * @see <a href="https://github.com/embabel/embabel-agent/issues/1758">Issue 1758</a>
     */
    @Nested
    inner class ApiFormatTests {

        /**
         * Pins the catalog contract the routing depends on: the `*-pro` models and the GPT-5.6
         * tiers ask for the Responses API, and every other model keeps the Chat Completions path it
         * has today. A model added to the wrong bucket is a production outage — either a 404 on
         * every call, or a working model silently rerouted onto an adapter it was never exercised
         * against.
         */
        @Test
        fun `shipped catalog routes the pro models and the GPT-5_6 tiers to Responses`() {
            val models = shippedCatalogue.effectiveModels()

            val byFormat = models.groupBy({ it.apiFormat }, { it.modelId })

            assertEquals(
                setOf(
                    "gpt-5-pro", "gpt-5.2-pro", "gpt-5.4-pro", "gpt-5.5-pro",
                    "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna",
                ),
                byFormat[OpenAiApiFormat.RESPONSES].orEmpty().toSet(),
                "Only the *-pro models and the GPT-5.6 tiers are served over /v1/responses",
            )
            assertTrue(
                byFormat[OpenAiApiFormat.CHAT_COMPLETIONS].orEmpty().none { it.endsWith("-pro") },
                "No *-pro model may keep the Chat Completions path: every call 404s",
            )
            assertEquals(
                models.size,
                byFormat.values.sumOf { it.size },
                "Every catalog model resolves to a transport",
            )
        }

        /**
         * GPT-5.6 dropped the `-pro` suffix — its tiers are Luna/Terra/Sol — so the suffix guard
         * above says nothing about them and their transport is pinned by name.
         *
         * They are served over both endpoints for a plain prompt, but Chat Completions refuses any
         * request that carries function tools:
         *
         *     400: Function tools with reasoning_effort are not supported for gpt-5.6-luna in
         *     /v1/chat/completions.
         *
         * These models default to `medium` reasoning effort server-side when the request omits it,
         * which every Embabel request does, so the combination is unavoidable on that endpoint.
         * Chat Completions would only accept tools at effort `none`, which is not something to
         * impose on models bought for their reasoning. Responses accepts the full range of efforts
         * alongside tools, and is what OpenAI documents for tool-calling workflows.
         */
        @Test
        fun `the GPT-5_6 tiers use Responses, the only transport that accepts their tool calls`() {
            val models = shippedCatalogue.effectiveModels()
                .filter { it.modelId.startsWith("gpt-5.6") }
                .associate { it.modelId to it.apiFormat }

            assertEquals(
                mapOf(
                    "gpt-5.6-sol" to OpenAiApiFormat.RESPONSES,
                    "gpt-5.6-terra" to OpenAiApiFormat.RESPONSES,
                    "gpt-5.6-luna" to OpenAiApiFormat.RESPONSES,
                ),
                models,
            )
        }

        /**
         * The field is additive. Catalogs written before it existed — including the ones users
         * override `embabel.agent.platform.models.openai` with — must keep working unchanged.
         */
        @Test
        fun `model without api_format falls back to Chat Completions`() {
            val loader = loaderFor(
                """
                models:
                  - name: legacy-model
                    model_id: gpt-legacy
                """.trimIndent()
            )

            assertEquals(
                OpenAiApiFormat.CHAT_COMPLETIONS,
                loader.loadAutoConfigMetadata().effectiveModels().single().apiFormat,
            )
        }

        @Test
        fun `api_format is read from YAML`() {
            val models = loaderFor(
                """
                models:
                  - name: responses-model
                    model_id: gpt-responses
                    api_format: RESPONSES
                  - name: chat-model
                    model_id: gpt-chat
                    api_format: CHAT_COMPLETIONS
                """.trimIndent()
            ).loadAutoConfigMetadata().effectiveModels().associateBy { it.modelId }

            assertEquals(OpenAiApiFormat.RESPONSES, models["gpt-responses"]?.apiFormat)
            assertEquals(OpenAiApiFormat.CHAT_COMPLETIONS, models["gpt-chat"]?.apiFormat)
        }

        /**
         * Characterises a sharp edge rather than endorsing it: the enum is matched case-sensitively,
         * so the lowercase spelling every other key in the file uses does not merely default the
         * field — it fails the whole document and the user loses every model, with only a logged
         * error to explain it. Change this test the day the mapper is taught to accept `responses`.
         */
        @Test
        fun `lowercase api_format is not recognised and discards the whole file`() {
            val result = loaderFor(
                """
                models:
                  - name: canary-model
                    model_id: gpt-canary
                  - name: responses-model
                    model_id: gpt-responses
                    api_format: responses
                """.trimIndent()
            ).loadAutoConfigMetadata()

            assertTrue(result.models.isEmpty(), "Enum matching is case-sensitive; the canary dies with it")
        }

        @Test
        fun `an unknown api_format discards the whole file`() {
            val result = loaderFor(
                """
                models:
                  - name: canary-model
                    model_id: gpt-canary
                  - name: typo-model
                    model_id: gpt-typo
                    api_format: RESPONSE
                """.trimIndent()
            ).loadAutoConfigMetadata()

            assertTrue(result.models.isEmpty())
        }

        /**
         * The pro models reject any temperature but the default, so the transport switch must not
         * cost them the [Gpt5ChatOptionsConverter] selection that `createOpenAiLlm` keys off.
         */
        @Test
        fun `pro models keep their existing special handling`() {
            val models = shippedCatalogue.effectiveModels()
                .filter { it.apiFormat == OpenAiApiFormat.RESPONSES }

            assertTrue(models.isNotEmpty(), "Guard against the filter silently matching nothing")
            models.forEach {
                assertEquals(
                    false,
                    it.specialHandling?.supportsTemperature,
                    "${it.modelId} must stay on the no-temperature converter",
                )
            }
        }
    }

    private fun assertSingleModel(result: OpenAiModelDefinitions): OpenAiModelDefinition {
        assertEquals(1, result.models.size)
        return result.models.first()
    }

    /**
     * JUnit's own `assertNotNull` returns `Unit`, which leaves the caller re-asserting nullability
     * on every subsequent access. This one hands the value back.
     */
    private fun <T : Any> assertPresent(value: T?, message: String): T {
        assertNotNull(value, message)
        return value!!
    }

    /**
     * A loader over an in-memory YAML document. The backing [ResourceLoader] answers the configured
     * path and nothing else, so a test that asks for the wrong location fails instead of silently
     * receiving the fixture anyway.
     */
    private fun loaderFor(content: String): OpenAiModelLoader {
        val resource = ByteArrayResource(content.toByteArray())
        val resourceLoader = object : ResourceLoader {
            override fun getResource(location: String) = when (location) {
                IN_MEMORY_CONFIG_PATH -> resource
                else -> error("Unexpected resource location: $location")
            }

            override fun getClassLoader() = javaClass.classLoader
        }
        return OpenAiModelLoader(resourceLoader, IN_MEMORY_CONFIG_PATH)
    }

    companion object {
        private const val IN_MEMORY_CONFIG_PATH = "memory:test-openai.yml"

        /**
         * A valid model prepended to every rejection fixture, so an empty result distinguishes
         * "the loader threw the file away" from "the invalid entry was never parsed at all".
         */
        private val CANARY_MODEL = """
            models:
              - name: canary-model
                model_id: gpt-canary
        """.trimIndent()

        /**
         * Loading is pure and the file is immutable, so the shipped catalogue is read once.
         */
        private val shippedCatalogue: OpenAiModelDefinitions by lazy {
            OpenAiModelLoader().loadAutoConfigMetadata()
        }
    }
}
