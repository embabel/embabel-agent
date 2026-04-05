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
        Constructor<ObservationKeys> constructor = ObservationKeys.class.getDeclaredConstructor();
        
        assertTrue(Modifier.isPrivate(constructor.getModifiers()),
                "Constructor must be private to prevent instantiation");
        
        constructor.setAccessible(true);
        constructor.newInstance();
    }

    @Test
    @DisplayName("Should format agent key correctly with 'agent:' prefix")
    void agentKeyFormatsCorrectly() {
        String result = ObservationKeys.agentKey("run123");
        
        assertEquals("agent:run123", result);
    }

    @Test
    @DisplayName("Should format action key correctly with 'action:runId:actionName' format")
    void actionKeyFormatsCorrectly() {
        String result = ObservationKeys.actionKey("run123", "processOrder");
        
        assertEquals("action:run123:processOrder", result);
    }

    @Test
    @DisplayName("Should format LLM key correctly with 'llm:runId:interactionId' format")
    void llmKeyFormatsCorrectly() {
        String result = ObservationKeys.llmKey("run123", "inter456");
        
        assertEquals("llm:run123:inter456", result);
    }

    @Test
    @DisplayName("Should format tool loop key correctly with 'tool-loop:' prefix")
    void toolLoopKeyFormatsCorrectly() {
        String result = ObservationKeys.toolLoopKey("run123", "inter456");
        
        assertEquals("tool-loop:run123:inter456", result);
    }

    @Test
    @DisplayName("Should format tool key correctly with 'tool:runId:toolName' format")
    void toolKeyFormatsCorrectly() {
        String result = ObservationKeys.toolKey("run123", "database");
        
        assertEquals("tool:run123:database", result);
    }

    @Test
    @DisplayName("Should format tool span name correctly")
    void toolSpanNameFormatsCorrectly() {
        String result = ObservationKeys.toolSpanName("http-client");
        
        assertEquals("tool:http-client", result);
    }

    @Test
    @DisplayName("Should format tool loop span name correctly")
    void toolLoopSpanNameFormatsCorrectly() {
        String result = ObservationKeys.toolLoopSpanName("inter456");
        
        assertEquals("tool-loop:", result);
    }

    @Test
    @DisplayName("Should handle empty strings gracefully without throwing exceptions")
    void handlesEmptyStrings() {
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
        String runId = "run-123_abc";
        String actionName = "process:order";
        
        String result = ObservationKeys.actionKey(runId, actionName);
        
        assertEquals("action:run-123_abc:process:order", result);
    }

    @Test
    @DisplayName("Should have correct prefix constants matching key format conventions")
    void constantsHaveCorrectPrefixes() {
        assertEquals("agent:", ObservationKeys.AGENT_PREFIX);
        assertEquals("action:", ObservationKeys.ACTION_PREFIX);
        assertEquals("llm:", ObservationKeys.LLM_PREFIX);
        assertEquals("tool-loop:", ObservationKeys.TOOL_LOOP_PREFIX);
        assertEquals("tool:", ObservationKeys.TOOL_PREFIX);
    }
}
