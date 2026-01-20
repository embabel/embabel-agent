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
package com.embabel.agent.api.tool;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Java interoperability tests for ReplanningTool.
 * These tests verify that ReplanningTool can be easily constructed and used from Java code.
 */
class ReplanningToolJavaTest {

    @Nested
    class ReplanningToolConstruction {

        @Test
        void createReplanningToolWithDefaultUpdater() {
            Tool delegate = Tool.create(
                    "route_user",
                    "Routes user to appropriate handler",
                    (input) -> Tool.Result.text("routed to support")
            );

            // Should be able to create with just delegate and reason
            ReplanningTool tool = new ReplanningTool(
                    delegate,
                    "Routing decision made"
            );

            assertNotNull(tool);
            assertEquals("route_user", tool.getDefinition().getName());
        }

        @Test
        void createReplanningToolWithCustomUpdater() {
            Tool delegate = Tool.create(
                    "classify_intent",
                    "Classifies user intent",
                    (input) -> Tool.Result.text("support")
            );

            // Should be able to create with a lambda for the blackboard updater
            ReplanningTool tool = new ReplanningTool(
                    delegate,
                    "Intent classified", (blackboard, content) -> {
                blackboard.set("intent", content);
                blackboard.set("confidence", 0.95);
            }
            );

            assertNotNull(tool);
            assertEquals("classify_intent", tool.getDefinition().getName());
        }
    }

    @Nested
    class ConditionalReplanningToolConstruction {

        @Test
        void createConditionalReplanningToolWithLambda() {
            Tool delegate = Tool.create(
                    "check_status",
                    "Checks status and may trigger replan",
                    (input) -> Tool.Result.text("needs_escalation")
            );

            // Should be able to create with a lambda for the decider
            ConditionalReplanningTool tool = new ConditionalReplanningTool(
                    delegate,
                    (context) -> {
                        if (context.getResultContent().equals("needs_escalation")) {
                            return new ReplanDecision(
                                    "Escalation needed",
                                    bb -> bb.set("escalate", true)
                            );
                        }
                        return null;
                    }
            );

            assertNotNull(tool);
            assertEquals("check_status", tool.getDefinition().getName());
        }

        @Test
        void createReplanDecisionWithDefaultUpdater() {
            // Should be able to create ReplanDecision with just a reason
            ReplanDecision decision = new ReplanDecision("Simple replan");

            assertEquals("Simple replan", decision.getReason());
            assertNotNull(decision.getBlackboardUpdater());
        }
    }
}
