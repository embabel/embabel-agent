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
package com.embabel.agent.api.tool.progressive;

import com.embabel.agent.api.annotation.LlmTool;
import com.embabel.agent.api.annotation.UnfoldingTools;
import com.embabel.agent.api.tool.Tool;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that UnfoldingTool factory methods are callable from Java
 * via static method syntax (i.e., {@code UnfoldingTool.method(...)}
 * not {@code UnfoldingTool.Companion.method(...)}).
 * <p>
 * Tests both the short-param convenience overloads (with defaults)
 * and the full-param versions.
 */
class UnfoldingToolJavaTest {

    // Test fixture: plain class with @LlmTool methods, no @UnfoldingTools annotation
    public static class PlainTools {
        @LlmTool(description = "Search for items")
        public String search(String query) {
            return "Results for: " + query;
        }

        @LlmTool(description = "Count items")
        public String count() {
            return "42";
        }
    }

    @UnfoldingTools(
        name = "annotated_tools",
        description = "Annotated tool group"
    )
    public static class AnnotatedTools {
        @LlmTool(description = "Do something")
        public String doSomething() {
            return "done";
        }
    }

    @UnfoldingTools(
        name = "greeting_tools",
        description = "Greeting operations"
    )
    public static class GreetingWithOptionalTitleUnfoldingTool {
        @LlmTool(description = "Greet a person, optionally with a title")
        public String greet(
                @LlmTool.Param(description = "Person's name") String name,
                @LlmTool.Param(description = "Optional title, e.g. Dr.", required = false) String title
        ) {
            if (title == null || title.isBlank()) {
                return "Hello, " + name + "!";
            }
            return "Hello, " + title + " " + name + "!";
        }
    }

    @UnfoldingTools(
        name = "parent_tools",
        description = "Parent tool group"
    )
    public static class ParentWithNestedUnfoldingTool {
        @LlmTool(description = "Parent action")
        public String parentAction() {
            return "parent";
        }

        @UnfoldingTools(
            name = "nested_greeting_tools",
            description = "Nested greeting operations"
        )
        public static class NestedGreetingTools {
            @LlmTool(description = "Greet a person, optionally with a title")
            public String greet(
                    @LlmTool.Param(description = "Person's name") String name,
                    @LlmTool.Param(description = "Optional title, e.g. Dr.", required = false) String title
            ) {
                if (title == null || title.isBlank()) {
                    return "Hello, " + name + "!";
                }
                return "Hello, " + title + " " + name + "!";
            }
        }
    }

    @Nested
    class OfTest {

        @Test
        void ofShortFormIsCallableAsStaticMethod() {
            Tool inner = Tool.create("inner", "Inner tool",
                Tool.InputSchema.empty(), input -> Tool.Result.text("result"));

            UnfoldingTool tool = UnfoldingTool.of(
                "my_tools",
                "My tools",
                List.of(inner)
            );

            assertEquals("my_tools", tool.getDefinition().getName());
            assertEquals("My tools", tool.getDefinition().getDescription());
            assertEquals(1, tool.getInnerTools().size());
            assertTrue(tool.getRemoveOnInvoke());
            assertNull(tool.getChildToolUsageNotes());
        }

        @Test
        void ofFullFormIsCallableAsStaticMethod() {
            Tool inner = Tool.create("inner", "Inner tool",
                Tool.InputSchema.empty(), input -> Tool.Result.text("result"));

            UnfoldingTool tool = UnfoldingTool.of(
                "my_tools",
                "My tools",
                List.of(inner),
                false,
                "Usage notes here"
            );

            assertEquals("my_tools", tool.getDefinition().getName());
            assertFalse(tool.getRemoveOnInvoke());
            assertEquals("Usage notes here", tool.getChildToolUsageNotes());
        }
    }

    @Nested
    class FromToolObjectTest {

        @Test
        void fromToolObjectShortFormIsCallableAsStaticMethod() {
            UnfoldingTool tool = UnfoldingTool.fromToolObject(
                new PlainTools(),
                "plain_tools",
                "Plain tool group"
            );

            assertEquals("plain_tools", tool.getDefinition().getName());
            assertEquals("Plain tool group", tool.getDefinition().getDescription());
            assertEquals(2, tool.getInnerTools().size());
            assertTrue(tool.getRemoveOnInvoke());
            assertNull(tool.getChildToolUsageNotes());

            List<String> names = tool.getInnerTools().stream()
                .map(t -> t.getDefinition().getName())
                .toList();
            assertTrue(names.contains("search"));
            assertTrue(names.contains("count"));
        }

        @Test
        void fromToolObjectFullFormIsCallableAsStaticMethod() {
            UnfoldingTool tool = UnfoldingTool.fromToolObject(
                new PlainTools(),
                "plain_tools",
                "Plain tool group",
                false,
                "Use search first"
            );

            assertFalse(tool.getRemoveOnInvoke());
            assertEquals("Use search first", tool.getChildToolUsageNotes());
        }

        @Test
        void fromToolObjectInnerToolsAreCallable() {
            UnfoldingTool tool = UnfoldingTool.fromToolObject(
                new PlainTools(),
                "tools",
                "Tools"
            );

            Tool searchTool = tool.getInnerTools().stream()
                .filter(t -> t.getDefinition().getName().equals("search"))
                .findFirst()
                .orElseThrow();

            Tool.Result result = searchTool.call("{\"query\": \"test\"}");

            assertInstanceOf(Tool.Result.Text.class, result);
            assertTrue(((Tool.Result.Text) result).getContent().contains("test"));
        }
    }

    @Nested
    class ByCategoryTest {

        @Test
        void byCategoryShortFormIsCallableAsStaticMethod() {
            Tool readTool = Tool.create("read", "Read data",
                Tool.InputSchema.empty(), input -> Tool.Result.text("read"));
            Tool writeTool = Tool.create("write", "Write data",
                Tool.InputSchema.empty(), input -> Tool.Result.text("wrote"));

            UnfoldingTool tool = UnfoldingTool.byCategory(
                "data_ops",
                "Data operations",
                Map.of(
                    "read", List.of(readTool),
                    "write", List.of(writeTool)
                )
            );

            assertEquals("data_ops", tool.getDefinition().getName());
            assertEquals(2, tool.getInnerTools().size());
        }

        @Test
        void byCategoryFullFormIsCallableAsStaticMethod() {
            Tool readTool = Tool.create("read", "Read data",
                Tool.InputSchema.empty(), input -> Tool.Result.text("read"));

            UnfoldingTool tool = UnfoldingTool.byCategory(
                "data_ops",
                "Data operations",
                Map.of("read", List.of(readTool)),
                "type",
                false,
                "Pick a category"
            );

            assertEquals("data_ops", tool.getDefinition().getName());
            assertFalse(tool.getRemoveOnInvoke());
            assertEquals("Pick a category", tool.getChildToolUsageNotes());
        }
    }

    @Nested
    class FromInstanceTest {

        @Test
        void fromInstanceShortFormIsCallableAsStaticMethod() {
            UnfoldingTool tool = UnfoldingTool.fromInstance(new AnnotatedTools());

            assertEquals("annotated_tools", tool.getDefinition().getName());
            assertEquals(1, tool.getInnerTools().size());
        }

        @Test
        void javaToolWithOptionalParamCanBeCalledWithoutThatParam() {
            var instance = new GreetingWithOptionalTitleUnfoldingTool();
            var unfoldingTool = UnfoldingTool.fromInstance(instance);
            var tool = unfoldingTool.getInnerTools().getFirst();

            var result = tool.call("{\"name\":\"Alice\"}");

            assertInstanceOf(Tool.Result.Text.class, result);
            assertEquals("Hello, Alice!", ((Tool.Result.Text) result).getContent());
        }

        @Test
        void javaToolWithOptionalParamCanBeCalledWithThatParam() {
            var instance = new GreetingWithOptionalTitleUnfoldingTool();
            var unfoldingTool = UnfoldingTool.fromInstance(instance);
            var tool = unfoldingTool.getInnerTools().getFirst();

            var result = tool.call("{\"name\":\"Smith\",\"title\":\"Dr.\"}");

            assertInstanceOf(Tool.Result.Text.class, result);
            assertEquals("Hello, Dr. Smith!", ((Tool.Result.Text) result).getContent());
        }

        @Test
        void optionalParamIsNotInRequiredArrayOfSchema() throws Exception {
            var instance = new GreetingWithOptionalTitleUnfoldingTool();
            var unfoldingTool = UnfoldingTool.fromInstance(instance);
            var tool = unfoldingTool.getInnerTools().getFirst();

            var schema = tool.getDefinition().getInputSchema().toJsonSchema();
            var schemaMap = new ObjectMapper().readValue(schema, Map.class);

            var required = (List<?>) schemaMap.get("required");
            assertNotNull(required, "Schema should have a 'required' array");
            assertTrue(required.contains("name"), "'name' should be required");
            assertFalse(required.contains("title"), "'title' must NOT be in required: " + required);
        }

        @Test
        void toolFromInstanceWithJavaUnfoldingToolsDiscoversAndExecutesOptionalParam() {
            var instance = new GreetingWithOptionalTitleUnfoldingTool();
            var tools = Tool.fromInstance(instance);

            assertEquals(1, tools.size());
            assertInstanceOf(UnfoldingTool.class, tools.getFirst());
            var unfoldingTool = (UnfoldingTool) tools.getFirst();
            var tool = unfoldingTool.getInnerTools().getFirst();

            var result = tool.call("{\"name\":\"Alice\"}");
            assertInstanceOf(Tool.Result.Text.class, result);
            assertEquals("Hello, Alice!", ((Tool.Result.Text) result).getContent());
        }

        @Test
        void nestedJavaUnfoldingToolWithOptionalParamCanBeCalledWithoutThatParam() {
            var instance = new ParentWithNestedUnfoldingTool();
            var unfoldingTool = UnfoldingTool.fromInstance(instance);

            var nestedTool = unfoldingTool.getInnerTools().stream()
                    .filter(t -> t instanceof UnfoldingTool && t.getDefinition().getName().equals("nested_greeting_tools"))
                    .map(t -> (UnfoldingTool) t)
                    .findFirst()
                    .orElseThrow();

            var greetTool = nestedTool.getInnerTools().getFirst();
            var result = greetTool.call("{\"name\":\"Alice\"}");

            assertInstanceOf(Tool.Result.Text.class, result);
            assertEquals("Hello, Alice!", ((Tool.Result.Text) result).getContent());
        }
    }

    @Nested
    class SafelyFromInstanceTest {

        @Test
        void safelyFromInstanceShortFormIsCallableAsStaticMethod() {
            UnfoldingTool tool = UnfoldingTool.safelyFromInstance(new AnnotatedTools());

            assertNotNull(tool);
            assertEquals("annotated_tools", tool.getDefinition().getName());
        }

        @Test
        void safelyFromInstanceReturnsNullForNonAnnotatedClass() {
            UnfoldingTool tool = UnfoldingTool.safelyFromInstance(new PlainTools());

            assertNull(tool);
        }
    }
}
