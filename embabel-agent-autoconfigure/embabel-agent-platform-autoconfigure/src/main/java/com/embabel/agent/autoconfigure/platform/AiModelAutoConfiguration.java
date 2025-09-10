/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.autoconfigure.platform;

import com.embabel.common.ai.model.*;
import jakarta.annotation.PostConstruct;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

import java.time.LocalDate;
import java.util.List;

/*
@AutoConfiguration
@AutoConfigureAfter(
        name = {
                "org.springframework.ai.autoconfigure.ollama.OllamaChatAutoConfiguration",
                "org.springframework.ai.autoconfigure.ollama.OllamaEmbeddingAutoConfiguration",
                "org.springframework.ai.autoconfigure.openai.OpenAiChatAutoConfiguration",
                "org.springframework.ai.autoconfigure.openai.OpenAiEmbeddingAutoConfiguration",
                "org.springframework.ai.autoconfigure.anthropic.AnthropicChatAutoConfiguration",
                "org.springframework.ai.autoconfigure.bedrock.BedrockChatAutoConfiguration",
                "org.springframework.ai.autoconfigure.vertexai.VertexAiGeminiChatAutoConfiguration"
        }
)
@ConditionalOnClass({ChatModel.class, Llm.class})
//@ConditionalOnBean(ChatModel.class)  // Only activate if Spring AI models exist
*/
public class AiModelAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(
            AiModelAutoConfiguration.class);


    @Bean
    public String modelRegistry(List<ChatModel> availableModels) {
        availableModels.forEach(model ->
                System.out.println("  - Discovered: " + model.getClass().getSimpleName()));
        return "Success";
    }

    @Bean
    public String embeddingModelRegistry(List<EmbeddingModel> availableModels) {
        availableModels.forEach(model ->
                System.out.println("  - Discovered: " + model.getClass().getSimpleName()));
        return "Success";
    }

    @Bean
    public EmbeddingService defaultOpenAiEmbeddingService(EmbeddingModel model) {
        return new EmbeddingService(
                "default-openai-embedding-service",
                "openai",
                 model
                );
    }

    @Bean
    public Llm gpt41mini(ChatModel chatModel) {

        // After
        var llm = new Llm(
                "gpt-4.1-mini",                  // name
                "openai",                 // provider
                chatModel,                // ChatModel instance
                new OptionsConverter() {
                    @Override
                    public @NotNull ChatOptions convertOptions(@NotNull LlmOptions options) {
                        return ChatOptions.builder().build(); // Simplified for example
                    }
                },                     // OptionsConverter instance
                LocalDate.now(),          // knowledgeCutoffDate
                List.of(),                // promptContributors
                new PricingModel() {
                    @Override
                    public double usdPerOutputToken() {
                        return 0;
                    }

                    @Override
                    public double usdPerInputToken() {
                        return 0;
                    }
                }        // pricingModel
        );

        return llm;
    }

    // Factory method for LlmMetadata
    private LlmMetadata createLlmMetadata() {
        // Create a concrete implementation or subclass of LlmMetadata
        return new LlmMetadata() {
            @Override
            public @NotNull String getProvider() {
                return "";
            }

            @Override
            public @NotNull String getName() {
                return "";
            }

            @Override
            public @Nullable PricingModel getPricingModel() {
                return null;
            }

            @Override
            public @Nullable LocalDate getKnowledgeCutoffDate() {
                return null;
            }
            // Implement abstract methods if any
        };
    }

    // Factory method for ModelMetadata
    private ModelMetadata createModelMetadata() {
        // Create a concrete implementation or subclass of ModelMetadata
        return new ModelMetadata() {
            @Override
            public @NotNull ModelType getType() {
                return null;
            }

            @Override
            public @NotNull String getProvider() {
                return "";
            }

            @Override
            public @NotNull String getName() {
                return "";
            }
            // Implement abstract methods if any
        };
    }

    @PostConstruct
    public void logStartup() {
        logger.info("🔄 Embabel Spring AI integration starting...");
    }

}