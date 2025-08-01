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
package com.embabel.agent.core;

import com.embabel.agent.core.support.InMemoryBlackboard;
import com.embabel.agent.testing.unit.FakeOperationContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessOptionsBuilderTest {

    @Test
    void builder() {
        var blackboard = new InMemoryBlackboard();

        var po = ProcessOptions.builder()
                .blackboard(blackboard)
                .test(true)
                .verbosity(vb -> vb
                        .showPrompts(true)
                        .showLlmResponses(true)
                        .debug(true)
                        .showPlanning(true)
                )
                .allowGoalChange(false)
                .budget(bb -> bb
                        .cost(1)
                        .actions(2)
                        .tokens(3)
                )
                .build();

        assertEquals(blackboard, po.getBlackboard());
        assertTrue(po.getTest());

        assertTrue(po.getVerbosity().getShowPrompts());
        assertTrue(po.getVerbosity().getShowLlmResponses());
        assertTrue(po.getVerbosity().getDebug());
        assertTrue(po.getVerbosity().getShowPlanning());

        assertEquals(1, po.getBudget().getCost());
        assertEquals(2, po.getBudget().getActions());
        assertEquals(3, po.getBudget().getTokens());
    }

}