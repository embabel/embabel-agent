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
package com.embabel.agent.spi.support.springai

import com.embabel.common.ai.model.DefaultOptionsConverter
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.OptionsConverter
import com.embabel.common.ai.model.PricingModel
import com.embabel.common.ai.prompt.KnowledgeCutoffDate
import com.embabel.common.ai.prompt.PromptContributor
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.ChatOptions
import java.time.LocalDate

class SpringAiLlmServiceTest {

    private val mockChatModel: ChatModel = mockk()

    @Nested
    inner class ConstructorTests {

        @Test
        fun `creates service with required parameters only`() {
            val service = SpringAiLlmService(
                name = "test-model",
                provider = "TestProvider",
                chatModel = mockChatModel,
            )

            assertThat(service.name).isEqualTo("test-model")
            assertThat(service.provider).isEqualTo("TestProvider")
            assertThat(service.chatModel).isEqualTo(mockChatModel)
            assertThat(service.model).isEqualTo(mockChatModel)
            assertThat(service.optionsConverter).isEqualTo(DefaultOptionsConverter)
            assertThat(service.knowledgeCutoffDate).isNull()
            assertThat(service.promptContributors).isEmpty()
            assertThat(service.pricingModel).isNull()
        }

        @Test
        fun `creates service with all parameters`() {
            val cutoffDate = LocalDate.of(2025, 1, 1)
            val pricingModel = PricingModel.usdPer1MTokens(1.0, 2.0)
            val customContributor = object : PromptContributor {
                override fun contribution() = "Custom contribution"
            }

            val service = SpringAiLlmService(
                name = "advanced-model",
                provider = "AdvancedProvider",
                chatModel = mockChatModel,
                optionsConverter = DefaultOptionsConverter,
                knowledgeCutoffDate = cutoffDate,
                promptContributors = listOf(customContributor),
                pricingModel = pricingModel,
            )

            assertThat(service.name).isEqualTo("advanced-model")
            assertThat(service.provider).isEqualTo("AdvancedProvider")
            assertThat(service.knowledgeCutoffDate).isEqualTo(cutoffDate)
            assertThat(service.promptContributors).hasSize(1)
            assertThat(service.pricingModel).isEqualTo(pricingModel)
        }

        @Test
        fun `automatically adds KnowledgeCutoffDate contributor when cutoff date is set`() {
            val cutoffDate = LocalDate.of(2025, 3, 15)

            val service = SpringAiLlmService(
                name = "model-with-cutoff",
                provider = "Provider",
                chatModel = mockChatModel,
                knowledgeCutoffDate = cutoffDate,
            )

            assertThat(service.promptContributors).hasSize(1)
            assertThat(service.promptContributors[0]).isInstanceOf(KnowledgeCutoffDate::class.java)
            val contributor = service.promptContributors[0] as KnowledgeCutoffDate
            assertThat(contributor.date).isEqualTo(cutoffDate)
        }
    }

    @Nested
    inner class WithKnowledgeCutoffDateTests {

        @Test
        fun `withKnowledgeCutoffDate returns new instance with updated date`() {
            val original = SpringAiLlmService(
                name = "test-model",
                provider = "Provider",
                chatModel = mockChatModel,
            )
            val newDate = LocalDate.of(2025, 6, 1)

            val updated = original.withKnowledgeCutoffDate(newDate)

            assertThat(updated).isNotSameAs(original)
            assertThat(updated.knowledgeCutoffDate).isEqualTo(newDate)
            assertThat(original.knowledgeCutoffDate).isNull()
        }

        @Test
        fun `withKnowledgeCutoffDate adds KnowledgeCutoffDate prompt contributor`() {
            val original = SpringAiLlmService(
                name = "test-model",
                provider = "Provider",
                chatModel = mockChatModel,
            )
            val newDate = LocalDate.of(2025, 6, 1)

            val updated = original.withKnowledgeCutoffDate(newDate)

            assertThat(updated.promptContributors).hasSize(1)
            assertThat(updated.promptContributors[0]).isInstanceOf(KnowledgeCutoffDate::class.java)
        }

        @Test
        fun `withKnowledgeCutoffDate preserves other properties`() {
            val pricingModel = PricingModel.usdPer1MTokens(1.0, 2.0)
            val original = SpringAiLlmService(
                name = "test-model",
                provider = "Provider",
                chatModel = mockChatModel,
                pricingModel = pricingModel,
            )

            val updated = original.withKnowledgeCutoffDate(LocalDate.of(2025, 1, 1))

            assertThat(updated.name).isEqualTo(original.name)
            assertThat(updated.provider).isEqualTo(original.provider)
            assertThat(updated.chatModel).isEqualTo(original.chatModel)
            assertThat(updated.pricingModel).isEqualTo(original.pricingModel)
        }
    }

    @Nested
    inner class WithPromptContributorTests {

        @Test
        fun `withPromptContributor returns new instance with added contributor`() {
            val original = SpringAiLlmService(
                name = "test-model",
                provider = "Provider",
                chatModel = mockChatModel,
            )
            val newContributor = object : PromptContributor {
                override fun contribution() = "New contribution"
            }

            val updated = original.withPromptContributor(newContributor)

            assertThat(updated).isNotSameAs(original)
            assertThat(updated.promptContributors).hasSize(1)
            assertThat(updated.promptContributors[0]).isEqualTo(newContributor)
            assertThat(original.promptContributors).isEmpty()
        }

        @Test
        fun `withPromptContributor accumulates contributors`() {
            val contributor1 = object : PromptContributor {
                override fun contribution() = "First"
            }
            val contributor2 = object : PromptContributor {
                override fun contribution() = "Second"
            }

            val service = SpringAiLlmService(
                name = "test-model",
                provider = "Provider",
                chatModel = mockChatModel,
            )
                .withPromptContributor(contributor1)
                .withPromptContributor(contributor2)

            assertThat(service.promptContributors).hasSize(2)
        }
    }

    @Nested
    inner class WithOptionsConverterTests {

        @Test
        fun `withOptionsConverter returns new instance with updated converter`() {
            val original = SpringAiLlmService(
                name = "test-model",
                provider = "Provider",
                chatModel = mockChatModel,
            )
            val customConverter = object : OptionsConverter<ChatOptions> {
                override fun convertOptions(options: LlmOptions): ChatOptions = mockk()
            }

            val updated = original.withOptionsConverter(customConverter)

            assertThat(updated).isNotSameAs(original)
            assertThat(updated.optionsConverter).isEqualTo(customConverter)
            assertThat(original.optionsConverter).isEqualTo(DefaultOptionsConverter)
        }
    }

    @Nested
    inner class CreateMessageSenderTests {

        @Test
        fun `createMessageSender returns SpringAiLlmMessageSender`() {
            val service = SpringAiLlmService(
                name = "test-model",
                provider = "Provider",
                chatModel = mockChatModel,
            )
            val options = LlmOptions()

            val sender = service.createMessageSender(options)

            assertThat(sender).isInstanceOf(SpringAiLlmMessageSender::class.java)
        }

        @Test
        fun `createMessageSender uses optionsConverter`() {
            var converterCalled = false
            val customConverter = object : OptionsConverter<ChatOptions> {
                override fun convertOptions(options: LlmOptions): ChatOptions {
                    converterCalled = true
                    return mockk()
                }
            }
            val service = SpringAiLlmService(
                name = "test-model",
                provider = "Provider",
                chatModel = mockChatModel,
                optionsConverter = customConverter,
            )

            service.createMessageSender(LlmOptions())

            assertThat(converterCalled).isTrue()
        }
    }

    @Nested
    inner class ModelPropertyTests {

        @Test
        fun `model property returns chatModel`() {
            val service = SpringAiLlmService(
                name = "test-model",
                provider = "Provider",
                chatModel = mockChatModel,
            )

            assertThat(service.model).isSameAs(service.chatModel)
        }
    }

    @Nested
    inner class FluentApiTests {

        @Test
        fun `fluent methods can be chained`() {
            val contributor = object : PromptContributor {
                override fun contribution() = "Contribution"
            }
            val customConverter = object : OptionsConverter<ChatOptions> {
                override fun convertOptions(options: LlmOptions): ChatOptions = mockk()
            }

            val service = SpringAiLlmService(
                name = "test-model",
                provider = "Provider",
                chatModel = mockChatModel,
            )
                .withKnowledgeCutoffDate(LocalDate.of(2025, 1, 1))
                .withPromptContributor(contributor)
                .withOptionsConverter(customConverter)

            assertThat(service.knowledgeCutoffDate).isEqualTo(LocalDate.of(2025, 1, 1))
            assertThat(service.promptContributors).hasSize(2) // KnowledgeCutoffDate + custom
            assertThat(service.optionsConverter).isEqualTo(customConverter)
        }
    }

    @Nested
    inner class DataClassTests {

        @Test
        fun `equals works correctly`() {
            val service1 = SpringAiLlmService(
                name = "test-model",
                provider = "Provider",
                chatModel = mockChatModel,
            )
            val service2 = SpringAiLlmService(
                name = "test-model",
                provider = "Provider",
                chatModel = mockChatModel,
            )

            assertThat(service1).isEqualTo(service2)
        }

        @Test
        fun `hashCode is consistent`() {
            val service1 = SpringAiLlmService(
                name = "test-model",
                provider = "Provider",
                chatModel = mockChatModel,
            )
            val service2 = SpringAiLlmService(
                name = "test-model",
                provider = "Provider",
                chatModel = mockChatModel,
            )

            assertThat(service1.hashCode()).isEqualTo(service2.hashCode())
        }

        @Test
        fun `copy creates independent instance`() {
            val original = SpringAiLlmService(
                name = "test-model",
                provider = "Provider",
                chatModel = mockChatModel,
            )

            val copy = original.copy(name = "different-model")

            assertThat(copy.name).isEqualTo("different-model")
            assertThat(original.name).isEqualTo("test-model")
        }
    }
}
