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
import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.api.dsl.Frog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoidAchievesGoalJavaTest {

    @Test
    void voidActionWithAchievesGoalIsRejected() {
        var reader = new AgentMetadataReader();
        var metadata = reader.createAgentMetadata(new VoidGoalAgent());

        assertNotNull(metadata, "Agent metadata should still be created — must not block startup");
        assertTrue(
                metadata.getGoals().stream().noneMatch(g -> g.getName().endsWith(".consumeFrog")),
                "No goal should be created for an @AchievesGoal method returning void: "
                        + metadata.getGoals());
    }

    @Test
    void infoStringDoesNotThrowForVoidGoal() {
        var reader = new AgentMetadataReader();
        var metadata = reader.createAgentMetadata(new VoidGoalAgent());
        assertNotNull(metadata);
        var agent = (com.embabel.agent.core.Agent) metadata;

        assertDoesNotThrow(agent::getPlanningSystem);
        assertDoesNotThrow(() -> agent.infoString(true, 0));
    }
}

@Agent(description = "void goal agent", planner = PlannerType.UTILITY)
class VoidGoalAgent {

    @Action
    public Frog makeFrog() {
        return new Frog("Kermit");
    }

    @AchievesGoal(description = "Consume a frog")
    @Action
    public void consumeFrog(Frog frog) {
    }
}