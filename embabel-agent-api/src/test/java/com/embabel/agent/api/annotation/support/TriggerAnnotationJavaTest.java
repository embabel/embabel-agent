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
package com.embabel.agent.api.annotation.support;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.AgentProcessStatusCode;
import com.embabel.agent.core.ProcessOptions;
import com.embabel.agent.test.integration.IntegrationTestUtils;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java test for trigger field on @Action annotation.
 */
class TriggerAnnotationJavaTest {

    // Domain types
    public record IncomingEvent(String payload) {
    }

    public record ExistingContext(String contextId) {
    }

    public record ProcessedEvent(String result) {
    }

    @Agent(description = "Java agent with trigger")
    public static class JavaTriggerAgent {

        @AchievesGoal(description = "Process incoming event")
        @Action(trigger = IncomingEvent.class)
        public ProcessedEvent processEvent(
                IncomingEvent event,
                ExistingContext context
        ) {
            return new ProcessedEvent("Processed " + event.payload() + " in context " + context.contextId());
        }
    }

    @Test
    void triggerWorksInJava() {
        var reader = new AgentMetadataReader();
        var metadata = reader.createAgentMetadata(new JavaTriggerAgent());
        assertNotNull(metadata);
        assertTrue(metadata instanceof com.embabel.agent.core.Agent);

        var ap = IntegrationTestUtils.dummyAgentPlatform();
        // Use LinkedHashMap to preserve insertion order - trigger (IncomingEvent) must be last
        var inputs = new LinkedHashMap<String, Object>();
        inputs.put("context", new ExistingContext("ctx-001"));
        inputs.put("it", new IncomingEvent("test-payload"));
        AgentProcess process = ap.runAgentFrom(
                (com.embabel.agent.core.Agent) metadata,
                new ProcessOptions(),
                inputs
        );

        assertEquals(AgentProcessStatusCode.COMPLETED, process.getStatus());
        var result = process.getValue("it", ProcessedEvent.class.getName());
        assertNotNull(result);
        assertTrue(result instanceof ProcessedEvent);
        assertTrue(((ProcessedEvent) result).result().contains("test-payload"));
        assertTrue(((ProcessedEvent) result).result().contains("ctx-001"));
    }
}
