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
package com.embabel.agent.config.models.gemini;

import com.embabel.agent.api.models.GeminiModels;
import com.embabel.agent.openai.OpenAiCompatibleModelFactory;
import com.embabel.agent.openai.OpenAiChatOptionsConverter;
import com.embabel.common.ai.model.Llm;
import com.embabel.common.ai.model.PerTokenPricingModel;
import com.embabel.common.util.ExcludeFromJacocoGeneratedReport;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;

/**
 * Configuration for well-known Google Gemini language and embedding models.
 * Provides bean definitions for various Gemini models with their corresponding
 * capabilities, knowledge cutoff dates, and pricing models.
 *
 * Uses OpenAI-compatible API endpoint for Gemini models.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GeminiProperties.class)
@ExcludeFromJacocoGeneratedReport(reason = "Gemini configuration can't be unit tested")
public class GeminiModelsConfig extends OpenAiCompatibleModelFactory {

    private static final Logger logger = LoggerFactory.getLogger(GeminiModelsConfig.class);

    private final GeminiProperties properties;

    public GeminiModelsConfig(
            @Value("${GEMINI_BASE_URL:https://generativelanguage.googleapis.com/v1beta/openai}") String baseUrl,
            @Value("${GEMINI_API_KEY}") String apiKey,
            ObjectProvider<ObservationRegistry> observationRegistry,
            GeminiProperties properties) {
        super(
                baseUrl,
                apiKey,
                null, // completionsPath
                null, // embeddingsPath
                observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP)
        );
        this.properties = properties;
        logger.info("Google Gemini models are available: {}", properties);
    }

    @Bean
    public Llm gemini3pro() {
        return openAiCompatibleLlm(
                GeminiModels.GEMINI_3_PRO,
                new PerTokenPricingModel(2.0, 12.0),
                GeminiModels.PROVIDER,
                LocalDate.of(2025, 11, 1),
                OpenAiChatOptionsConverter.INSTANCE,
                properties.retryTemplate(GeminiModels.GEMINI_3_PRO)
        );
    }

    @Bean
    public Llm gemini25pro() {
        return openAiCompatibleLlm(
                GeminiModels.GEMINI_2_5_PRO,
                new PerTokenPricingModel(1.25, 10.0),
                GeminiModels.PROVIDER,
                LocalDate.of(2025, 11, 1),
                OpenAiChatOptionsConverter.INSTANCE,
                properties.retryTemplate(GeminiModels.GEMINI_2_5_PRO)
        );
    }

    @Bean
    public Llm gemini25flash() {
        return openAiCompatibleLlm(
                GeminiModels.GEMINI_2_5_FLASH,
                new PerTokenPricingModel(0.30, 2.50),
                GeminiModels.PROVIDER,
                LocalDate.of(2025, 1, 1),
                OpenAiChatOptionsConverter.INSTANCE,
                properties.retryTemplate(GeminiModels.GEMINI_2_5_FLASH)
        );
    }

    @Bean
    public Llm gemini25flashlite() {
        return openAiCompatibleLlm(
                GeminiModels.GEMINI_2_5_FLASH_LITE,
                new PerTokenPricingModel(0.10, 0.40),
                GeminiModels.PROVIDER,
                LocalDate.of(2025, 1, 1),
                OpenAiChatOptionsConverter.INSTANCE,
                properties.retryTemplate(GeminiModels.GEMINI_2_5_FLASH_LITE)
        );
    }

    @Bean
    public Llm gemini20flashexp() {
        return openAiCompatibleLlm(
                GeminiModels.GEMINI_2_0_FLASH_EXP,
                new PerTokenPricingModel(0.10, 0.40),
                GeminiModels.PROVIDER,
                LocalDate.of(2024, 8, 1),
                OpenAiChatOptionsConverter.INSTANCE,
                properties.retryTemplate(GeminiModels.GEMINI_2_0_FLASH_EXP)
        );
    }

    @Bean
    public Llm gemini15pro() {
        return openAiCompatibleLlm(
                GeminiModels.GEMINI_1_5_PRO,
                new PerTokenPricingModel(1.25, 5.0),
                GeminiModels.PROVIDER,
                LocalDate.of(2023, 11, 1),
                OpenAiChatOptionsConverter.INSTANCE,
                properties.retryTemplate(GeminiModels.GEMINI_1_5_PRO)
        );
    }

    @Bean
    public Llm gemini15flash() {
        return openAiCompatibleLlm(
                GeminiModels.GEMINI_1_5_FLASH,
                new PerTokenPricingModel(0.075, 0.30),
                GeminiModels.PROVIDER,
                LocalDate.of(2024, 5, 1),
                OpenAiChatOptionsConverter.INSTANCE,
                properties.retryTemplate(GeminiModels.GEMINI_1_5_FLASH)
        );
    }

    @Bean
    public Llm gemini15flash8b() {
        return openAiCompatibleLlm(
                GeminiModels.GEMINI_1_5_FLASH_8B,
                new PerTokenPricingModel(0.0375, 0.15),
                GeminiModels.PROVIDER,
                LocalDate.of(2024, 5, 1),
                OpenAiChatOptionsConverter.INSTANCE,
                properties.retryTemplate(GeminiModels.GEMINI_1_5_FLASH_8B)
        );
    }
}