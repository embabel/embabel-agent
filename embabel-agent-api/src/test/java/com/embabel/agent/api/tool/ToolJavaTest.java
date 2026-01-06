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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java interoperability tests for the Tool API.
 * These tests verify that Tool can be easily constructed and used from Java code.
 */
class ToolJavaTest {

    @Nested
    class ToolCreation {

        @Test
        void createToolWithHandler() {
            Tool tool = Tool.create(
                "greet",
                "Greets a person by name",
                (input) -> Tool.Result.text("Hello!")
            );

            assertNotNull(tool);
            assertEquals("greet", tool.getDefinition().getName());
            assertEquals("Greets a person by name", tool.getDefinition().getDescription());

            Tool.Result result = tool.call("{}");
            assertInstanceOf(Tool.Result.Text.class, result);
            assertEquals("Hello!", ((Tool.Result.Text) result).getContent());
        }

        @Test
        void createToolWithParameters() {
            Tool tool = Tool.create(
                "add",
                "Adds two numbers",
                Tool.InputSchema.of(
                    new Tool.Parameter("a", Tool.ParameterType.INTEGER, "First number", true, null),
                    new Tool.Parameter("b", Tool.ParameterType.INTEGER, "Second number", true, null)
                ),
                (input) -> Tool.Result.text("Result: 42")
            );

            assertNotNull(tool);
            assertEquals("add", tool.getDefinition().getName());
            assertEquals(2, tool.getDefinition().getInputSchema().getParameters().size());

            String schema = tool.getDefinition().getInputSchema().toJsonSchema();
            assertTrue(schema.contains("\"a\""));
            assertTrue(schema.contains("\"b\""));
            assertTrue(schema.contains("integer"));
        }

        @Test
        void createToolWithMetadata() {
            Tool tool = Tool.create(
                "direct_tool",
                "Returns result directly",
                Tool.Metadata.create(true),
                (input) -> Tool.Result.text("Direct result")
            );

            assertTrue(tool.getMetadata().getReturnDirect());
        }

        @Test
        void createToolWithParametersAndMetadata() {
            Tool tool = Tool.create(
                "full_tool",
                "A fully configured tool",
                Tool.InputSchema.of(
                    new Tool.Parameter("query", Tool.ParameterType.STRING, "Search query", true, null)
                ),
                Tool.Metadata.create(false),
                (input) -> Tool.Result.text("Search results")
            );

            assertNotNull(tool);
            assertEquals("full_tool", tool.getDefinition().getName());
            assertEquals(1, tool.getDefinition().getInputSchema().getParameters().size());
            assertFalse(tool.getMetadata().getReturnDirect());
        }
    }

    @Nested
    class ToolExecution {

        @Test
        void executeToolReturnsError() {
            Tool tool = Tool.create(
                "error_tool",
                "Always fails",
                (input) -> Tool.Result.error("Something went wrong", null)
            );

            Tool.Result result = tool.call("{}");
            assertInstanceOf(Tool.Result.Error.class, result);
            assertEquals("Something went wrong", ((Tool.Result.Error) result).getMessage());
        }

        @Test
        void executeToolReturnsArtifact() {
            Tool tool = Tool.create(
                "artifact_tool",
                "Returns an artifact",
                (input) -> Tool.Result.withArtifact(
                    "Generated file",
                    new byte[]{1, 2, 3}
                )
            );

            Tool.Result result = tool.call("{}");
            assertInstanceOf(Tool.Result.WithArtifact.class, result);
            Tool.Result.WithArtifact artifactResult = (Tool.Result.WithArtifact) result;
            assertEquals("Generated file", artifactResult.getContent());
            assertArrayEquals(new byte[]{1, 2, 3}, (byte[]) artifactResult.getArtifact());
        }
    }

    @Nested
    class ToolDefinitionBuilding {

        @Test
        void buildInputSchemaWithMultipleParameters() {
            Tool.InputSchema schema = Tool.InputSchema.of(
                new Tool.Parameter("name", Tool.ParameterType.STRING, "The name", true, null),
                new Tool.Parameter("age", Tool.ParameterType.INTEGER, "The age", false, null),
                new Tool.Parameter("active", Tool.ParameterType.BOOLEAN, "Is active", true, null)
            );

            assertEquals(3, schema.getParameters().size());
            String json = schema.toJsonSchema();
            assertTrue(json.contains("\"name\""));
            assertTrue(json.contains("\"age\""));
            assertTrue(json.contains("\"active\""));
            assertTrue(json.contains("\"required\""));
        }

        @Test
        void buildParameterWithEnumValues() {
            Tool.Parameter param = new Tool.Parameter(
                "status",
                Tool.ParameterType.STRING,
                "Status of the item",
                true,
                java.util.List.of("PENDING", "APPROVED", "REJECTED")
            );

            Tool.InputSchema schema = Tool.InputSchema.of(param);
            String json = schema.toJsonSchema();
            assertTrue(json.contains("\"enum\""));
            assertTrue(json.contains("PENDING"));
            assertTrue(json.contains("APPROVED"));
            assertTrue(json.contains("REJECTED"));
        }

        @Test
        void buildEmptyInputSchema() {
            Tool.InputSchema schema = Tool.InputSchema.empty();

            assertTrue(schema.getParameters().isEmpty());
            String json = schema.toJsonSchema();
            assertTrue(json.contains("\"properties\":{}"));
        }
    }

}
