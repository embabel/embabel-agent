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
package com.embabel.agent.openai;

import com.embabel.agent.api.models.DeepSeekModels;
import com.embabel.agent.spi.LlmService;
import com.embabel.common.ai.model.EmbeddingService;
import com.embabel.common.ai.model.ModelPlatform;
import com.embabel.common.ai.model.ProviderCredential;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The platforms as a Java application constructs them: with {@code new}, and with or without the
 * optional arguments.
 */
class OpenAiCompatiblePlatformJavaTest {

    private static final String GATEWAY_URL = "https://gateway.example.com/v1";

    @Test
    void theCatchAllIsConstructedWithAProviderAndABaseUrl() {
        ModelPlatform gateway = new OpenAiCompatiblePlatform("OurGateway", GATEWAY_URL);

        // With no kinds given, it builds chat and embedding.
        assertEquals(OpenAiCompatiblePlatform.CHAT_AND_EMBEDDING, gateway.getKinds());
        // A chat model for the gateway's key is built without a request.
        var chat = gateway.model(new ProviderCredential("OurGateway", "sk-test"), LlmService.KIND, "some-model");
        assertNotNull(chat);
        assertEquals("OurGateway", chat.getProvider());
    }

    @Test
    void theCatchAllTakesKindsAndTimeouts() {
        var timeouts = new OpenAiClientTimeouts(Duration.ofSeconds(5), Duration.ofMinutes(2));
        var chatOnly = new OpenAiCompatiblePlatform("OurGateway", GATEWAY_URL, Set.of(LlmService.KIND), timeouts);

        assertEquals(Set.of(LlmService.KIND), chatOnly.getKinds());
        assertEquals(timeouts, chatOnly.getTimeouts());
        // Embedding is not one of its kinds, so it builds none.
        assertNull(chatOnly.model(new ProviderCredential("OurGateway", "sk-test"), EmbeddingService.KIND, "some-embedding"));
    }

    @Test
    void theNamedPlatformsNeedNoArguments() {
        var deepSeek = new DeepSeekPlatform();

        assertEquals(DeepSeekModels.PROVIDER, deepSeek.getProvider());
        assertEquals(Set.of(LlmService.KIND), deepSeek.getKinds());
        assertEquals(OpenAiCompatiblePlatform.CHAT_AND_EMBEDDING, new OpenAiPlatform().getKinds());
        assertEquals(OpenAiCompatiblePlatform.CHAT_AND_EMBEDDING, new MistralAiPlatform().getKinds());
    }
}
