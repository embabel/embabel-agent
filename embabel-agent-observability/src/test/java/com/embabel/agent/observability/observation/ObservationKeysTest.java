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
package com.embabel.agent.observability.observation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link ObservationKeys}.
 */
class ObservationKeysTest {

    @Test
    @DisplayName("Should have private constructor to prevent instantiation")
    void hasPrivateConstructor() throws Exception {
        // Arrange
        Constructor<ObservationKeys> constructor = ObservationKeys.class.getDeclaredConstructor();
        
        // Assert
        assertTrue(Modifier.isPrivate(constructor.getModifiers()),
                "Constructor must be private to prevent instantiation");
    }

    @Test
    @DisplayName("Should format agent key correctly with 'agent:' prefix")
    void agentKeyFormatsCorrectly() {
        // Act
        String result = ObservationKeys.agentKey("run123");
        
        // Assert
        assertEquals("agent:run123", result);
    }

    @Test
    @DisplayName("Should format action key correctly with 'action:runId:actionName' format")
    void actionKeyFormatsCorrectly() {
        // Act
        String result = ObservationKeys.actionKey("run123", "processOrder");
        
        // Assert
        assertEquals("action:run123:processOrder", result);
    }

    @Test
    @DisplayName("Should format LLM key correctly with 'llm:runId:interactionId' format")
    void llmKeyFormatsCorrectly() {
        // Act
        String result = ObservationKeys.llmKey("run123", "inter456");
        
        // Assert
        assertEquals("llm:run123:inter456", result);
    }

    @Test
    @DisplayName("Should format tool loop key correctly with 'tool-loop:' prefix")
    void toolLoopKeyFormatsCorrectly() {
        // Act
        String result = ObservationKeys.toolLoopKey("run123", "inter456");
        
        // Assert
        assertEquals("tool-loop:run123:inter456", result);
    }

    @Test
    @DisplayName("Should format tool key correctly with 'tool:runId:toolName' format")
    void toolKeyFormatsCorrectly() {
        // Act
        String result = ObservationKeys.toolKey("run123", "database");
        
        // Assert
        assertEquals("tool:run123:database", result);
    }

    @Test
    @DisplayName("Should format tool span name correctly")
    void toolSpanNameFormatsCorrectly() {
        // Act
        String result = ObservationKeys.toolSpanName("http-client");
        
        // Assert
        assertEquals("tool:http-client", result);
    }

    @Test
    @DisplayName("Should format tool loop span name correctly")
    void toolLoopSpanNameFormatsCorrectly() {
        // Act
        String result = ObservationKeys.toolLoopSpanName("inter456");
        
        // Assert
        assertEquals("tool-loop:", result);
    }

    @Test
    @DisplayName("Should handle empty strings gracefully without throwing exceptions")
    void handlesEmptyStrings() {
        // Act & Assert
        assertEquals("agent:", ObservationKeys.agentKey(""));
        assertEquals("action::", ObservationKeys.actionKey("", ""));
        assertEquals("llm::", ObservationKeys.llmKey("", ""));
        assertEquals("tool-loop::", ObservationKeys.toolLoopKey("", ""));
        assertEquals("tool::", ObservationKeys.toolKey("", ""));
        assertEquals("tool:", ObservationKeys.toolSpanName(""));
    }

    @Test
    @DisplayName("Should handle special characters in keys (colons, underscores, hyphens)")
    void handlesSpecialCharacters() {
        // Arrange
        String runId = "run-123_abc";
        String actionName = "process:order";
        
        // Act
        String result = ObservationKeys.actionKey(runId, actionName);
        
        // Assert
        assertEquals("action:run-123_abc:process:order", result);
    }

    @Test
    @DisplayName("Should have correct prefix constants matching key format conventions")
    void constantsHaveCorrectPrefixes() {
        // Assert
        assertEquals("agent:", ObservationKeys.AGENT_PREFIX);
        assertEquals("action:", ObservationKeys.ACTION_PREFIX);
        assertEquals("llm:", ObservationKeys.LLM_PREFIX);
        assertEquals("tool-loop:", ObservationKeys.TOOL_LOOP_PREFIX);
        assertEquals("tool:", ObservationKeys.TOOL_PREFIX);
    }
}
