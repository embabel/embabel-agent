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
package com.embabel.common.ai.autoconfig

import com.embabel.common.ai.model.PerTokenPricingModel
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader
import java.time.LocalDate

class DecisionModelCatalogLoaderTest {

    @Nested
    inner class ValidCatalog {

        @Test
        fun `loads every model in file order`() {
            val models = loader("valid").loadAutoConfigMetadata().models

            assertThat(models.map { it.name }).containsExactly("first", "second")
            assertThat(models.map { it.modelId }).containsExactly("provider/first", "provider/second")
            assertThat(models.map { it.displayName }).containsExactly("First", null)
        }
    }

    @Nested
    inner class UnusableCatalog {

        @Test
        fun `a missing catalog fails naming the resource`() {
            val path = path("missing")

            assertThatThrownBy { loader("missing").loadAutoConfigMetadata() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage("Test model catalog $path not found")
        }

        @Test
        fun `an invalid catalog fails naming the resource`() {
            assertThatThrownBy { loader("malformed").loadAutoConfigMetadata() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageStartingWith("Test model catalog ${path("malformed")} is invalid: ")
        }

        @Test
        fun `an empty catalog fails naming the resource`() {
            assertThatThrownBy { loader("empty").loadAutoConfigMetadata() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage("Test model catalog ${path("empty")} is invalid: the catalog defines no models")
        }

        @Test
        fun `a catalog with repeated names fails naming them`() {
            assertThatThrownBy { loader("duplicate").loadAutoConfigMetadata() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage(
                    "Test model catalog ${path("duplicate")} is invalid: " +
                        "Test model names must be unique. Repeated names: [first]"
                )
        }
    }

    private fun path(catalog: String) = "classpath:decision-catalogs/$catalog.yml"

    private fun loader(catalog: String) = TestDecisionModelLoader(path(catalog))

    data class TestModelDefinitions(
        override val models: List<TestModelDefinition> = emptyList(),
    ) : LlmAutoConfigProvider<TestModelDefinition>

    data class TestModelDefinition(
        override val name: String,
        override val modelId: String,
        override val displayName: String? = null,
    ) : LlmAutoConfigMetadata {
        override val knowledgeCutoffDate: LocalDate? get() = null
        override val pricingModel: PerTokenPricingModel? get() = null
    }

    class TestDecisionModelLoader(configPath: String) :
        DecisionModelCatalogLoader<TestModelDefinitions>(DefaultResourceLoader(), configPath) {

        override fun getProviderClass() = TestModelDefinitions::class

        override fun createEmptyProvider() = TestModelDefinitions()

        override fun getProviderName() = "Test"
    }
}
