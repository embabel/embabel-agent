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
        Constructor<GeminiModels> constructor = GeminiModels.class.getDeclaredConstructor();
        
        assertTrue(Modifier.isPrivate(constructor.getModifiers()), 
            "Constructor must be private to prevent instantiation");
        
        constructor.setAccessible(true);
        assertDoesNotThrow(() -> constructor.newInstance(), 
            "Private constructor should be callable via reflection");
    }

    @Test
    @DisplayName("Should provide all Gemini 3.1 model constants")
    void providesGemini3_1Models() {
        assertNotNull(GeminiModels.GEMINI_3_1_PRO_PREVIEW, 
            "GEMINI_3_1_PRO_PREVIEW constant should not be null");
        assertNotNull(GeminiModels.GEMINI_3_1_PRO_PREVIEW_CUSTOMTOOLS, 
            "GEMINI_3_1_PRO_PREVIEW_CUSTOMTOOLS constant should not be null");
        assertNotNull(GeminiModels.GEMINI_3_1_FLASH_LITE_PREVIEW, 
            "GEMINI_3_1_FLASH_LITE_PREVIEW constant should not be null");
        
        assertEquals("gemini-3.1-pro-preview", GeminiModels.GEMINI_3_1_PRO_PREVIEW);
        assertEquals("gemini-3.1-pro-preview-customtools", GeminiModels.GEMINI_3_1_PRO_PREVIEW_CUSTOMTOOLS);
        assertEquals("gemini-3.1-flash-lite-preview", GeminiModels.GEMINI_3_1_FLASH_LITE_PREVIEW);
    }

    @Test
    @DisplayName("Should provide all Gemini 2.5 model constants")
    void providesGemini2_5Models() {
        assertNotNull(GeminiModels.GEMINI_2_5_PRO, 
            "GEMINI_2_5_PRO constant should not be null");
        assertNotNull(GeminiModels.GEMINI_2_5_FLASH, 
            "GEMINI_2_5_FLASH constant should not be null");
        assertNotNull(GeminiModels.GEMINI_2_5_FLASH_LITE, 
            "GEMINI_2_5_FLASH_LITE constant should not be null");
        
        assertEquals("gemini-2.5-pro", GeminiModels.GEMINI_2_5_PRO);
        assertEquals("gemini-2.5-flash", GeminiModels.GEMINI_2_5_FLASH);
        assertEquals("gemini-2.5-flash-lite", GeminiModels.GEMINI_2_5_FLASH_LITE);
    }

    @Test
    @DisplayName("Should provide all Gemini 2.0 model constants")
    void providesGemini2_0Models() {
        assertNotNull(GeminiModels.GEMINI_2_0_FLASH, 
            "GEMINI_2_0_FLASH constant should not be null");
        assertNotNull(GeminiModels.GEMINI_2_0_FLASH_LITE, 
            "GEMINI_2_0_FLASH_LITE constant should not be null");
        
        assertEquals("gemini-2.0-flash", GeminiModels.GEMINI_2_0_FLASH);
        assertEquals("gemini-2.0-flash-lite", GeminiModels.GEMINI_2_0_FLASH_LITE);
    }

    @Test
    @DisplayName("Should provide correct provider name 'Google'")
    void providesCorrectProvider() {
        assertNotNull(GeminiModels.PROVIDER, "PROVIDER constant should not be null");
        assertEquals("Google", GeminiModels.PROVIDER, 
            "Provider should be 'Google'");
    }

    @Test
    @DisplayName("Should provide embedding model constants")
    void providesEmbeddingModels() {
        assertNotNull(GeminiModels.TEXT_EMBEDDING_004, 
            "TEXT_EMBEDDING_004 constant should not be null");
        assertNotNull(GeminiModels.DEFAULT_TEXT_EMBEDDING_MODEL, 
            "DEFAULT_TEXT_EMBEDDING_MODEL constant should not be null");
        
        assertEquals("text-embedding-004", GeminiModels.TEXT_EMBEDDING_004);
        assertEquals(GeminiModels.TEXT_EMBEDDING_004, GeminiModels.DEFAULT_TEXT_EMBEDDING_MODEL,
            "Default embedding model should point to TEXT_EMBEDDING_004");
    }

    @Test
    @DisplayName("Should follow 'gemini-' naming convention for all models")
    void followsNamingConvention() {
        assertTrue(GeminiModels.GEMINI_3_1_PRO_PREVIEW.startsWith("gemini-"), 
            "Model names should start with 'gemini-' prefix");
        assertTrue(GeminiModels.GEMINI_2_5_FLASH.startsWith("gemini-"), 
            "Model names should start with 'gemini-' prefix");
        assertTrue(GeminiModels.GEMINI_2_0_FLASH.startsWith("gemini-"), 
            "Model names should start with 'gemini-' prefix");
    }

}
