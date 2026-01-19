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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java interoperability tests for the Tools utility class.
 * These tests verify that Tools methods can be called from Java with clean syntax.
 */
class ToolsJavaTest {

    @Nested
    class ReplanAlwaysTests {

        @Test
        void replanAlwaysWrapsToolCorrectly() {
            Tool delegate = Tool.create(
                    "my_tool",
                    "A simple tool",
                    input -> Tool.Result.text("result")
            );

            var wrapped = Tool.replanAlways(delegate);

            assertNotNull(wrapped);
            assertEquals("my_tool", wrapped.getDefinition().getName());
            assertInstanceOf(ConditionalReplanningTool.class, wrapped);
        }
    }

    @Nested
    class ReplanWhenTests {

        @Test
        void replanWhenWithPredicate() {
            var delegate = Tool.create(
                    "status_checker",
                    "Checks status",
                    input -> Tool.Result.text("result")
            );

            // Use replanWhen with a lambda predicate
            var wrapped = Tool.replanWhen(
                    delegate,
                    (String artifact) -> artifact.equals("escalate")
            );

            assertNotNull(wrapped);
            assertEquals("status_checker", wrapped.getDefinition().getName());
            assertInstanceOf(ConditionalReplanningTool.class, wrapped);
        }
    }

    @Nested
    class ConditionalReplanTests {

        @Test
        void conditionalReplanWithDecider() {
            var delegate = Tool.create(
                    "classifier",
                    "Classifies intent",
                    input -> Tool.Result.text("result")
            );

            // Use conditionalReplan with a lambda decider
            Tool wrapped = Tool.conditionalReplan(
                    delegate,
                    (String artifact, ReplanContext context) -> {
                        if (artifact.equals("support")) {
                            return new ReplanDecision(
                                    "Classified as support",
                                    bb -> bb.set("intent", artifact)
                            );
                        }
                        return null;
                    }
            );

            assertNotNull(wrapped);
            assertEquals("classifier", wrapped.getDefinition().getName());
            assertInstanceOf(ConditionalReplanningTool.class, wrapped);
        }
    }

    @Nested
    class FormatToolTreeTests {

        @Test
        void formatToolTreeWithEmptyList() {
            var result = Tool.formatToolTree("MyAgent", List.of());
            assertEquals("MyAgent has no tools", result);
        }

        @Test
        void formatToolTreeWithMultipleTools() {
            var tool1 = Tool.create("tool_a", "First tool", input -> Tool.Result.text("a"));
            var tool2 = Tool.create("tool_b", "Second tool", input -> Tool.Result.text("b"));

            var result = Tool.formatToolTree("MyAgent", List.of(tool1, tool2));

            assertTrue(result.contains("MyAgent"));
            assertTrue(result.contains("tool_a"));
            assertTrue(result.contains("tool_b"));
        }
    }
}
