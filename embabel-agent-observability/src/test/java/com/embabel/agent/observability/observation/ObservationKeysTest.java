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
    void hasPrivateConstructor() throws Exception {
        Constructor<ObservationKeys> constructor = ObservationKeys.class.getDeclaredConstructor();
        
        assertTrue(Modifier.isPrivate(constructor.getModifiers()),
                "Constructor must be private to prevent instantiation");
        
        constructor.setAccessible(true);
        constructor.newInstance();
    }

    @Test
    void agentKeyFormatsCorrectly() {
        String result = ObservationKeys.agentKey("run123");
        
        assertEquals("agent:run123", result);
    }

    @Test
    void actionKeyFormatsCorrectly() {
        String result = ObservationKeys.actionKey("run123", "processOrder");
        
        assertEquals("action:run123:processOrder", result);
    }

    @Test
    void llmKeyFormatsCorrectly() {
        String result = ObservationKeys.llmKey("run123", "inter456");
        
        assertEquals("llm:run123:inter456", result);
    }

    @Test
    void toolLoopKeyFormatsCorrectly() {
        String result = ObservationKeys.toolLoopKey("run123", "inter456");
        
        assertEquals("tool-loop:run123:inter456", result);
    }

    @Test
    void toolKeyFormatsCorrectly() {
        String result = ObservationKeys.toolKey("run123", "database");
        
        assertEquals("tool:run123:database", result);
    }

    @Test
    void toolSpanNameFormatsCorrectly() {
        String result = ObservationKeys.toolSpanName("http-client");
        
        assertEquals("tool:http-client", result);
    }

    @Test
    void toolLoopSpanNameFormatsCorrectly() {
        String result = ObservationKeys.toolLoopSpanName("inter456");
        
        assertEquals("tool-loop:", result);
    }

    @Test
    void handlesEmptyStrings() {
        assertEquals("agent:", ObservationKeys.agentKey(""));
        assertEquals("action::", ObservationKeys.actionKey("", ""));
        assertEquals("llm::", ObservationKeys.llmKey("", ""));
        assertEquals("tool-loop::", ObservationKeys.toolLoopKey("", ""));
        assertEquals("tool::", ObservationKeys.toolKey("", ""));
        assertEquals("tool:", ObservationKeys.toolSpanName(""));
    }

    @Test
    void handlesSpecialCharacters() {
        String runId = "run-123_abc";
        String actionName = "process:order";
        
        String result = ObservationKeys.actionKey(runId, actionName);
        
        assertEquals("action:run-123_abc:process:order", result);
    }

    @Test
    void constantsHaveCorrectPrefixes() {
        assertEquals("agent:", ObservationKeys.AGENT_PREFIX);
        assertEquals("action:", ObservationKeys.ACTION_PREFIX);
        assertEquals("llm:", ObservationKeys.LLM_PREFIX);
        assertEquals("tool-loop:", ObservationKeys.TOOL_LOOP_PREFIX);
        assertEquals("tool:", ObservationKeys.TOOL_PREFIX);
    }
}
