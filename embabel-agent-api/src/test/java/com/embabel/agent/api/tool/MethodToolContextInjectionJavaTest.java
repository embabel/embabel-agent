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

import com.embabel.agent.api.annotation.LlmTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests verifying that {@link MethodTool} (Java variant) correctly handles
 * {@link ToolCallContext} injection into Java {@code @LlmTool}-annotated methods.
 *
 * <p>These tests exercise the {@code JavaMethodTool} path, which uses
 * {@code java.lang.reflect.Method} and positional argument arrays rather
 * than Kotlin's {@code KFunction} and {@code callBy}.
 */
class MethodToolContextInjectionJavaTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ---- Java test fixtures ----

    /**
     * Tool with a ToolCallContext parameter alongside a regular parameter.
     */
    public static class JavaContextAwareTool {
        public ToolCallContext lastContext;

        @LlmTool(description = "Search with auth context")
        public String search(
                @LlmTool.Param(description = "Search query") String query,
                ToolCallContext context
        ) {
            this.lastContext = context;
            String token = context.<String>get("authToken");
            return "Results for '" + query + "' with token=" + (token != null ? token : "none");
        }
    }

    /**
     * Tool with only a ToolCallContext parameter — no LLM-facing parameters.
     */
    public static class JavaContextOnlyTool {
        public ToolCallContext lastContext;

        @LlmTool(description = "Audit action")
        public String audit(ToolCallContext context) {
            this.lastContext = context;
            String userId = context.<String>get("userId");
            return "Audit logged for " + (userId != null ? userId : "anonymous");
        }
    }

    /**
     * Tool without any ToolCallContext parameter — backward compatibility.
     */
    public static class JavaNoContextTool {
        @LlmTool(description = "Simple greeting")
        public String greet(@LlmTool.Param(description = "Name") String name) {
            return "Hello, " + name + "!";
        }
    }

    /**
     * Tool with multiple regular parameters and ToolCallContext.
     */
    public static class JavaMultiParamTool {
        public ToolCallContext lastContext;

        @LlmTool(description = "Transfer funds")
        public String transfer(
                @LlmTool.Param(description = "Source account") String from,
                @LlmTool.Param(description = "Destination account") String to,
                @LlmTool.Param(description = "Amount") int amount,
                ToolCallContext context
        ) {
            this.lastContext = context;
            String tenantId = context.<String>get("tenantId");
            return "Transferred " + amount + " from " + from + " to " + to +
                    " (tenant=" + (tenantId != null ? tenantId : "unknown") + ")";
        }
    }

    /**
     * Tool where ToolCallContext appears in the middle of the parameter list.
     */
    public static class JavaContextInMiddleTool {
        public ToolCallContext lastContext;

        @LlmTool(description = "Process with context in middle")
        public String process(
                @LlmTool.Param(description = "Input") String input,
                ToolCallContext context,
                @LlmTool.Param(description = "Mode") String mode
        ) {
            this.lastContext = context;
            return input + ":" + mode;
        }
    }

    @Nested
    class ContextInjection {

        @Test
        void contextIsInjectedIntoMethodWithToolCallContextParameter() {
            var instance = new JavaContextAwareTool();
            var tools = Tool.fromInstance(instance, objectMapper);
            var tool = tools.get(0);

            var ctx = ToolCallContext.of(Map.of("authToken", "bearer-secret-123"));
            var result = tool.call("{\"query\":\"embabel agent\"}", ctx);

            assertInstanceOf(Tool.Result.Text.class, result);
            assertEquals(
                    "Results for 'embabel agent' with token=bearer-secret-123",
                    ((Tool.Result.Text) result).getContent()
            );
            assertNotNull(instance.lastContext);
            assertEquals("bearer-secret-123", instance.lastContext.<String>get("authToken"));
        }

        @Test
        void emptyContextIsInjectedWhenSingleArgCallIsUsed() {
            var instance = new JavaContextAwareTool();
            var tools = Tool.fromInstance(instance, objectMapper);
            var tool = tools.get(0);

            var result = tool.call("{\"query\":\"test\"}");

            assertInstanceOf(Tool.Result.Text.class, result);
            assertEquals(
                    "Results for 'test' with token=none",
                    ((Tool.Result.Text) result).getContent()
            );
            assertNotNull(instance.lastContext);
            assertTrue(instance.lastContext.isEmpty());
        }

        @Test
        void contextOnlyToolReceivesContext() {
            var instance = new JavaContextOnlyTool();
            var tools = Tool.fromInstance(instance, objectMapper);
            var tool = tools.get(0);

            var ctx = ToolCallContext.of(Map.of("userId", "user-42"));
            var result = tool.call("{}", ctx);

            assertInstanceOf(Tool.Result.Text.class, result);
            assertEquals("Audit logged for user-42", ((Tool.Result.Text) result).getContent());
            assertEquals("user-42", instance.lastContext.<String>get("userId"));
        }

        @Test
        void methodWithoutToolCallContextWorksNormally() {
            var instance = new JavaNoContextTool();
            var tools = Tool.fromInstance(instance, objectMapper);
            var tool = tools.get(0);

            var result = tool.call("{\"name\":\"Claude\"}");

            assertInstanceOf(Tool.Result.Text.class, result);
            assertEquals("Hello, Claude!", ((Tool.Result.Text) result).getContent());
        }

        @Test
        void methodWithoutToolCallContextIgnoresProvidedContext() {
            var instance = new JavaNoContextTool();
            var tools = Tool.fromInstance(instance, objectMapper);
            var tool = tools.get(0);

            var ctx = ToolCallContext.of(Map.of("authToken", "should-be-ignored"));
            var result = tool.call("{\"name\":\"Claude\"}", ctx);

            assertInstanceOf(Tool.Result.Text.class, result);
            assertEquals("Hello, Claude!", ((Tool.Result.Text) result).getContent());
        }

        @Test
        void contextWorksAlongsideMultipleRegularParameters() {
            var instance = new JavaMultiParamTool();
            var tools = Tool.fromInstance(instance, objectMapper);
            var tool = tools.get(0);

            var ctx = ToolCallContext.of(Map.of("tenantId", "acme-corp"));
            var result = tool.call(
                    "{\"from\":\"checking\",\"to\":\"savings\",\"amount\":500}",
                    ctx
            );

            assertInstanceOf(Tool.Result.Text.class, result);
            assertEquals(
                    "Transferred 500 from checking to savings (tenant=acme-corp)",
                    ((Tool.Result.Text) result).getContent()
            );
            assertEquals("acme-corp", instance.lastContext.<String>get("tenantId"));
        }

        @Test
        void contextWorksWhenDeclaredInMiddleOfParameterList() {
            var instance = new JavaContextInMiddleTool();
            var tools = Tool.fromInstance(instance, objectMapper);
            var tool = tools.get(0);

            var ctx = ToolCallContext.of(Map.of("traceId", "trace-abc"));
            var result = tool.call("{\"input\":\"data\",\"mode\":\"fast\"}", ctx);

            assertInstanceOf(Tool.Result.Text.class, result);
            assertEquals("data:fast", ((Tool.Result.Text) result).getContent());
            assertNotNull(instance.lastContext);
            assertEquals("trace-abc", instance.lastContext.<String>get("traceId"));
        }
    }

    @Nested
    class SchemaExclusion {

        @Test
        @SuppressWarnings("unchecked")
        void toolCallContextParameterIsExcludedFromInputSchema() throws Exception {
            var instance = new JavaContextAwareTool();
            var tools = Tool.fromInstance(instance, objectMapper);
            var tool = tools.get(0);

            var schema = tool.getDefinition().getInputSchema().toJsonSchema();
            var schemaMap = objectMapper.readValue(schema, Map.class);
            var properties = (Map<String, Object>) schemaMap.get("properties");

            assertTrue(properties.containsKey("query"), "Schema should include 'query' parameter");
            assertFalse(properties.containsKey("context"), "Schema must NOT include ToolCallContext parameter");
        }

        @Test
        @SuppressWarnings("unchecked")
        void schemaForContextOnlyToolHasNoParameters() throws Exception {
            var instance = new JavaContextOnlyTool();
            var tools = Tool.fromInstance(instance, objectMapper);
            var tool = tools.get(0);

            var schema = tool.getDefinition().getInputSchema().toJsonSchema();
            var schemaMap = objectMapper.readValue(schema, Map.class);
            var properties = (Map<String, Object>) schemaMap.getOrDefault("properties", Map.of());

            assertTrue(properties.isEmpty(),
                    "Schema should have no properties when only ToolCallContext is declared");
        }

        @Test
        @SuppressWarnings("unchecked")
        void schemaForMultiParamToolExcludesOnlyToolCallContext() throws Exception {
            var instance = new JavaMultiParamTool();
            var tools = Tool.fromInstance(instance, objectMapper);
            var tool = tools.get(0);

            var schema = tool.getDefinition().getInputSchema().toJsonSchema();
            var schemaMap = objectMapper.readValue(schema, Map.class);
            var properties = (Map<String, Object>) schemaMap.get("properties");

            assertEquals(3, properties.size(), "Should have exactly 3 parameters (from, to, amount)");
            assertTrue(properties.containsKey("from"));
            assertTrue(properties.containsKey("to"));
            assertTrue(properties.containsKey("amount"));
            assertFalse(properties.containsKey("context"), "ToolCallContext must be excluded");
        }

        @Test
        @SuppressWarnings("unchecked")
        void schemaExcludesContextWhenInMiddleOfParameterList() throws Exception {
            var instance = new JavaContextInMiddleTool();
            var tools = Tool.fromInstance(instance, objectMapper);
            var tool = tools.get(0);

            var schema = tool.getDefinition().getInputSchema().toJsonSchema();
            var schemaMap = objectMapper.readValue(schema, Map.class);
            var properties = (Map<String, Object>) schemaMap.get("properties");

            assertEquals(2, properties.size(), "Should have exactly 2 parameters (input, mode)");
            assertTrue(properties.containsKey("input"));
            assertTrue(properties.containsKey("mode"));
            assertFalse(properties.containsKey("context"), "ToolCallContext must be excluded");
        }
    }
}
