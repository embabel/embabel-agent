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

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.api.dsl.Frog;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.AgentProcessStatusCode;
import com.embabel.agent.core.ProcessOptions;
import com.embabel.agent.test.integration.IntegrationTestUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test void return handling for Java utility actions.
 * This test is in Java to verify that void returns work correctly in Java.
 */
class UtilityActionJavaTest {

    @Test
    void acceptVoidReturnAndInvokeTwoActions() {
        AgentMetadataReader reader = new AgentMetadataReader();
        Utility2Actions1VoidNoGoalJava instance = new Utility2Actions1VoidNoGoalJava();
        var metadata = reader.createAgentMetadata(instance);

        assertNotNull(metadata);
        assertEquals(2, metadata.getActions().size());

        var ap = IntegrationTestUtils.dummyAgentPlatform();
        var agent = (com.embabel.agent.core.Agent) metadata;
        AgentProcess agentProcess = ap.runAgentFrom(
                agent,
                ProcessOptions.DEFAULT.withPlannerType(PlannerType.UTILITY),
                java.util.Collections.emptyMap()
        );

        assertEquals(
                AgentProcessStatusCode.STUCK,
                agentProcess.getStatus(),
                "Should be stuck, not finished: status=" + agentProcess.getStatus()
        );
        assertTrue(
                instance.invokedThing2,
                "Should have invoked second method"
        );
    }
}

@Agent(
        description = "thing",
        planner = PlannerType.UTILITY
)
class Utility2Actions1VoidNoGoalJava {

    boolean invokedThing2 = false;

    @Action
    public Frog makeFrog() {
        return new Frog("Kermit");
    }

    @Action
    public void thing2(Frog frog) {
        invokedThing2 = true;
    }
}
