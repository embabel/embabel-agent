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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for schema generation with List parameters in Java.
 * This verifies that generic type information is preserved for Java methods.
 */
class JavaListParameterSchemaTest {

    /**
     * Java tools with List parameters - mirrors MathTools pattern
     */
    static class JavaMathTools {

        @LlmTool(description = "Calculate the mean of a list of numbers")
        public double mean(List<Double> numbers) {
            if (numbers == null || numbers.isEmpty()) {
                return 0.0;
            }
            return numbers.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        }

        @LlmTool(description = "Find the minimum value in a list of numbers")
        public double min(List<Double> numbers) {
            if (numbers == null || numbers.isEmpty()) {
                return Double.NaN;
            }
            return numbers.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
        }

        @LlmTool(description = "Sum a list of integers")
        public int sumIntegers(List<Integer> numbers) {
            if (numbers == null || numbers.isEmpty()) {
                return 0;
            }
            return numbers.stream().mapToInt(Integer::intValue).sum();
        }

        @LlmTool(description = "Join a list of strings")
        public String joinStrings(List<String> strings) {
            if (strings == null || strings.isEmpty()) {
                return "";
            }
            return String.join("", strings);
        }
    }

    @Test
    void schemaForJavaMethodWithListOfDoublesShouldIncludeItemsType() {
        List<Tool> tools = Tool.fromInstance(new JavaMathTools());
        Tool meanTool = tools.stream()
                .filter(t -> t.getDefinition().getName().equals("mean"))
                .findFirst()
                .orElseThrow();

        String json = meanTool.getDefinition().getInputSchema().toJsonSchema();

        // Should have array type
        assertTrue(json.contains("\"type\":\"array\""),
                "Schema should have array type: " + json);

        // IMPORTANT: Should have items property specifying element type
        // Without this, LLMs don't know what type of elements the array should contain
        assertTrue(json.contains("\"items\""),
                "Schema should have items property for array: " + json);
        assertTrue(
                json.contains("\"items\":{\"type\":\"number\"}") ||
                        json.contains("\"items\": {\"type\": \"number\"}"),
                "Array items should have number type for List<Double>: " + json
        );
    }

    @Test
    void schemaForJavaMethodWithListOfIntegersShouldIncludeItemsType() {
        List<Tool> tools = Tool.fromInstance(new JavaMathTools());
        Tool sumTool = tools.stream()
                .filter(t -> t.getDefinition().getName().equals("sumIntegers"))
                .findFirst()
                .orElseThrow();

        String json = sumTool.getDefinition().getInputSchema().toJsonSchema();

        // Should have array type
        assertTrue(json.contains("\"type\":\"array\""),
                "Schema should have array type: " + json);

        // Should have items property with integer type
        assertTrue(json.contains("\"items\""),
                "Schema should have items property for array: " + json);
        assertTrue(
                json.contains("\"items\":{\"type\":\"integer\"}") ||
                        json.contains("\"items\": {\"type\": \"integer\"}"),
                "Array items should have integer type for List<Integer>: " + json
        );
    }

    @Test
    void schemaForJavaMethodWithListOfStringsShouldIncludeItemsType() {
        List<Tool> tools = Tool.fromInstance(new JavaMathTools());
        Tool joinTool = tools.stream()
                .filter(t -> t.getDefinition().getName().equals("joinStrings"))
                .findFirst()
                .orElseThrow();

        String json = joinTool.getDefinition().getInputSchema().toJsonSchema();

        // Should have array type
        assertTrue(json.contains("\"type\":\"array\""),
                "Schema should have array type: " + json);

        // Should have items property with string type
        assertTrue(json.contains("\"items\""),
                "Schema should have items property for array: " + json);
        assertTrue(
                json.contains("\"items\":{\"type\":\"string\"}") ||
                        json.contains("\"items\": {\"type\": \"string\"}"),
                "Array items should have string type for List<String>: " + json
        );
    }
}
