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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java interoperability tests for Tool.fromFunction().
 */
class ToolFromFunctionJavaTest {

    // Test records
    record AddRequest(int a, int b) {}
    record AddResult(int sum) {}
    record GreetRequest(String name) {}

    @Test
    void createsToolWithLambda() {
        Tool tool = Tool.fromFunction(
            "add",
            "Add two numbers",
            AddRequest.class,
            AddResult.class,
            input -> new AddResult(input.a() + input.b())
        );

        assertEquals("add", tool.getDefinition().getName());
        assertEquals("Add two numbers", tool.getDefinition().getDescription());
    }

    @Test
    void executesWithTypedInputAndOutput() {
        Tool tool = Tool.fromFunction(
            "add",
            "Add two numbers",
            AddRequest.class,
            AddResult.class,
            input -> new AddResult(input.a() + input.b())
        );

        Tool.Result result = tool.call("{\"a\": 7, \"b\": 3}");

        assertInstanceOf(Tool.Result.Text.class, result);
        assertTrue(((Tool.Result.Text) result).getContent().contains("\"sum\":10"));
    }

    @Test
    void generatesSchemaFromInputType() {
        Tool tool = Tool.fromFunction(
            "add",
            "Add numbers",
            AddRequest.class,
            AddResult.class,
            input -> new AddResult(input.a() + input.b())
        );

        String schema = tool.getDefinition().getInputSchema().toJsonSchema();
        assertTrue(schema.contains("\"a\""));
        assertTrue(schema.contains("\"b\""));
    }

    @Test
    void stringOutputNotDoubleSerialized() {
        Tool tool = Tool.fromFunction(
            "greet",
            "Greet someone",
            GreetRequest.class,
            String.class,
            input -> "Hello " + input.name() + "!"
        );

        Tool.Result result = tool.call("{\"name\": \"World\"}");

        assertEquals("Hello World!", ((Tool.Result.Text) result).getContent());
    }

    @Test
    void toolResultPassesThrough() {
        Tool tool = Tool.fromFunction(
            "greet",
            "Greet someone",
            GreetRequest.class,
            Tool.Result.class,
            input -> Tool.Result.text("Hi " + input.name())
        );

        Tool.Result result = tool.call("{\"name\": \"Java\"}");

        assertEquals("Hi Java", ((Tool.Result.Text) result).getContent());
    }

    @Test
    void exceptionsConvertedToErrorResult() {
        Tool tool = Tool.fromFunction(
            "fail",
            "Always fails",
            GreetRequest.class,
            String.class,
            input -> { throw new RuntimeException("Intentional failure"); }
        );

        Tool.Result result = tool.call("{\"name\": \"Test\"}");

        assertInstanceOf(Tool.Result.Error.class, result);
        assertEquals("Intentional failure", ((Tool.Result.Error) result).getMessage());
    }

    @Test
    void withCustomMetadata() {
        Tool tool = Tool.fromFunction(
            "add",
            "Add numbers",
            AddRequest.class,
            AddResult.class,
            Tool.Metadata.create(true),
            input -> new AddResult(input.a() + input.b())
        );

        assertTrue(tool.getMetadata().getReturnDirect());
    }
}
