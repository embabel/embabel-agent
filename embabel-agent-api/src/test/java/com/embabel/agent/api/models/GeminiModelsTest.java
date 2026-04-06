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
package com.embabel.agent.api.models;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GeminiModels}.
 * Validates model constants and utility class constraints.
 */
class GeminiModelsTest {

    @Test
    @DisplayName("Should have private constructor to prevent instantiation")
    void hasPrivateConstructor() throws Exception {
        // Arrange
        Constructor<GeminiModels> constructor = GeminiModels.class.getDeclaredConstructor();
        
        // Assert
        assertTrue(Modifier.isPrivate(constructor.getModifiers()), 
            "Constructor must be private to prevent instantiation");
    }

    @Test
    @DisplayName("Should provide all Gemini 3.1 model constants")
    void providesGemini3_1Models() {
        // Assert
        assertEquals("gemini-3.1-pro-preview", GeminiModels.GEMINI_3_1_PRO_PREVIEW);
        assertEquals("gemini-3.1-pro-preview-customtools", GeminiModels.GEMINI_3_1_PRO_PREVIEW_CUSTOMTOOLS);
        assertEquals("gemini-3.1-flash-lite-preview", GeminiModels.GEMINI_3_1_FLASH_LITE_PREVIEW);
    }

    @Test
    @DisplayName("Should provide all Gemini 2.5 model constants")
    void providesGemini2_5Models() {
        // Assert
        assertEquals("gemini-2.5-pro", GeminiModels.GEMINI_2_5_PRO);
        assertEquals("gemini-2.5-flash", GeminiModels.GEMINI_2_5_FLASH);
        assertEquals("gemini-2.5-flash-lite", GeminiModels.GEMINI_2_5_FLASH_LITE);
    }

    @Test
    @DisplayName("Should provide all Gemini 2.0 model constants")
    void providesGemini2_0Models() {
        // Assert
        assertEquals("gemini-2.0-flash", GeminiModels.GEMINI_2_0_FLASH);
        assertEquals("gemini-2.0-flash-lite", GeminiModels.GEMINI_2_0_FLASH_LITE);
    }

    @Test
    @DisplayName("Should provide correct provider name 'Google'")
    void providesCorrectProvider() {
        // Assert
        assertEquals("Google", GeminiModels.PROVIDER, "Provider should be 'Google'");
    }

    @Test
    @DisplayName("Should provide embedding model constants")
    void providesEmbeddingModels() {
        // Assert
        assertEquals("text-embedding-004", GeminiModels.TEXT_EMBEDDING_004);
        assertEquals(GeminiModels.TEXT_EMBEDDING_004, GeminiModels.DEFAULT_TEXT_EMBEDDING_MODEL,
            "Default embedding model should point to TEXT_EMBEDDING_004");
    }

    @Test
    @DisplayName("Should follow 'gemini-' naming convention for all models")
    void followsNamingConvention() {
        // Assert
        assertTrue(GeminiModels.GEMINI_3_1_PRO_PREVIEW.startsWith("gemini-"), 
            "Model names should start with 'gemini-' prefix");
        assertTrue(GeminiModels.GEMINI_2_5_FLASH.startsWith("gemini-"), 
            "Model names should start with 'gemini-' prefix");
        assertTrue(GeminiModels.GEMINI_2_0_FLASH.startsWith("gemini-"), 
            "Model names should start with 'gemini-' prefix");
    }

}
