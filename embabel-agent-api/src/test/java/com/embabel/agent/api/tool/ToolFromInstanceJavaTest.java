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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Tool.fromInstance with various Java class visibility scenarios.
 * These tests verify that tools can be created from package-protected and
 * inner classes, which requires setAccessible for reflection.
 */
class ToolFromInstanceJavaTest {

    // Package-protected class with public methods
    static class PackageProtectedTools {
        @LlmTool(description = "Add two numbers")
        public int add(int a, int b) {
            return a + b;
        }

        @LlmTool(description = "Multiply two numbers")
        public int multiply(int a, int b) {
            return a * b;
        }
    }

    // Public class for comparison
    public static class PublicTools {
        @LlmTool(description = "Subtract two numbers")
        public int subtract(int a, int b) {
            return a - b;
        }
    }

    // Private inner class
    private static class PrivateTools {
        @LlmTool(description = "Divide two numbers")
        public double divide(double a, double b) {
            return a / b;
        }
    }

    // Package-protected class with package-protected methods
    static class PackageProtectedMethodTools {
        @LlmTool(description = "Package method")
        String packageMethod(String input) {
            return "Package: " + input;
        }
    }

    // Package-protected class with private method (should not be exposed)
    static class PrivateMethodTools {
        @LlmTool(description = "Private method")
        private String privateMethod(String input) {
            return "Private: " + input;
        }

        @LlmTool(description = "Public method")
        public String publicMethod(String input) {
            return "Public: " + input;
        }
    }

    @Nested
    class PackageProtectedClass {

        @Test
        void fromInstanceWorksWithPackageProtectedClass() {
            PackageProtectedTools tools = new PackageProtectedTools();

            List<Tool> result = Tool.fromInstance(tools);

            assertEquals(2, result.size());
            List<String> names = result.stream()
                .map(t -> t.getDefinition().getName())
                .toList();
            assertTrue(names.contains("add"));
            assertTrue(names.contains("multiply"));
        }

        @Test
        void toolFromPackageProtectedClassCanBeInvoked() {
            PackageProtectedTools tools = new PackageProtectedTools();
            List<Tool> result = Tool.fromInstance(tools);

            Tool addTool = result.stream()
                .filter(t -> t.getDefinition().getName().equals("add"))
                .findFirst()
                .orElseThrow();

            Tool.Result addResult = addTool.call("{\"a\": 5, \"b\": 3}");

            assertInstanceOf(Tool.Result.Text.class, addResult);
            assertEquals("8", ((Tool.Result.Text) addResult).getContent());
        }
    }

    @Nested
    class PublicClass {

        @Test
        void fromInstanceWorksWithPublicClass() {
            PublicTools tools = new PublicTools();

            List<Tool> result = Tool.fromInstance(tools);

            assertEquals(1, result.size());
            assertEquals("subtract", result.get(0).getDefinition().getName());
        }

        @Test
        void toolFromPublicClassCanBeInvoked() {
            PublicTools tools = new PublicTools();
            List<Tool> result = Tool.fromInstance(tools);

            Tool subtractTool = result.get(0);
            Tool.Result subtractResult = subtractTool.call("{\"a\": 10, \"b\": 4}");

            assertInstanceOf(Tool.Result.Text.class, subtractResult);
            assertEquals("6", ((Tool.Result.Text) subtractResult).getContent());
        }
    }

    @Nested
    class PrivateClass {

        @Test
        void fromInstanceWorksWithPrivateInnerClass() {
            PrivateTools tools = new PrivateTools();

            List<Tool> result = Tool.fromInstance(tools);

            assertEquals(1, result.size());
            assertEquals("divide", result.get(0).getDefinition().getName());
        }

        @Test
        void toolFromPrivateInnerClassCanBeInvoked() {
            PrivateTools tools = new PrivateTools();
            List<Tool> result = Tool.fromInstance(tools);

            Tool divideTool = result.get(0);
            Tool.Result divideResult = divideTool.call("{\"a\": 10.0, \"b\": 2.0}");

            assertInstanceOf(Tool.Result.Text.class, divideResult);
            assertEquals("5.0", ((Tool.Result.Text) divideResult).getContent());
        }
    }

    @Nested
    class PackageProtectedMethods {

        @Test
        void fromInstanceWorksWithPackageProtectedMethods() {
            PackageProtectedMethodTools tools = new PackageProtectedMethodTools();

            List<Tool> result = Tool.fromInstance(tools);

            assertEquals(1, result.size());
            assertEquals("packageMethod", result.get(0).getDefinition().getName());
        }

        @Test
        void toolWithPackageProtectedMethodCanBeInvoked() {
            PackageProtectedMethodTools tools = new PackageProtectedMethodTools();
            List<Tool> result = Tool.fromInstance(tools);

            Tool packageTool = result.get(0);
            Tool.Result packageResult = packageTool.call("{\"input\": \"test\"}");

            assertInstanceOf(Tool.Result.Text.class, packageResult);
            assertEquals("Package: test", ((Tool.Result.Text) packageResult).getContent());
        }
    }

    @Nested
    class PrivateMethods {

        @Test
        void fromInstanceIncludesPrivateMethods() {
            PrivateMethodTools tools = new PrivateMethodTools();

            List<Tool> result = Tool.fromInstance(tools);

            // Both private and public methods with @LlmTool should be included
            assertEquals(2, result.size());
            List<String> names = result.stream()
                .map(t -> t.getDefinition().getName())
                .toList();
            assertTrue(names.contains("privateMethod"));
            assertTrue(names.contains("publicMethod"));
        }

        @Test
        void toolWithPrivateMethodCanBeInvoked() {
            PrivateMethodTools tools = new PrivateMethodTools();
            List<Tool> result = Tool.fromInstance(tools);

            Tool privateTool = result.stream()
                .filter(t -> t.getDefinition().getName().equals("privateMethod"))
                .findFirst()
                .orElseThrow();

            Tool.Result privateResult = privateTool.call("{\"input\": \"secret\"}");

            assertInstanceOf(Tool.Result.Text.class, privateResult);
            assertEquals("Private: secret", ((Tool.Result.Text) privateResult).getContent());
        }
    }

    @Nested
    class SafelyFromInstance {

        @Test
        void safelyFromInstanceWorksWithPackageProtectedClass() {
            PackageProtectedTools tools = new PackageProtectedTools();

            List<Tool> result = Tool.safelyFromInstance(tools);

            assertEquals(2, result.size());
        }

        @Test
        void safelyFromInstanceWorksWithPrivateInnerClass() {
            PrivateTools tools = new PrivateTools();

            List<Tool> result = Tool.safelyFromInstance(tools);

            assertEquals(1, result.size());
        }
    }

    // Test fixtures for Issue #1326 - Complex type schema generation
    public static class WrappedType {
        public int internalId;
        public String name;

        public WrappedType() {}

        public WrappedType(int internalId, String name) {
            this.internalId = internalId;
            this.name = name;
        }
    }

    public static class Address {
        public String street;
        public String city;
        public String zipCode;

        public Address() {}

        public Address(String street, String city, String zipCode) {
            this.street = street;
            this.city = city;
            this.zipCode = zipCode;
        }
    }

    public static class ComplexParameterTools {
        @LlmTool(description = "Get information using a wrapped type")
        public String getInformation(WrappedType someType) {
            return "ID: " + someType.internalId + ", Name: " + someType.name;
        }

        @LlmTool(description = "Process an address")
        public String processAddress(Address address) {
            return address.street + ", " + address.city + " " + address.zipCode;
        }

        @LlmTool(description = "Mix of simple and complex params")
        public String mixedParams(String label, WrappedType wrapped, int count) {
            return label + ": " + wrapped.name + " x " + count;
        }
    }

    /**
     * Tests for Issue #1326: Input schema details are missing for tools defined with @LlmTool
     * when using complex types as parameters.
     */
    @Nested
    class ComplexTypeSchemaGeneration {

        @Test
        void schemaForComplexTypeParameterShouldIncludeNestedProperties() {
            ComplexParameterTools tools = new ComplexParameterTools();
            List<Tool> result = Tool.fromInstance(tools);

            Tool tool = result.stream()
                .filter(t -> t.getDefinition().getName().equals("getInformation"))
                .findFirst()
                .orElseThrow();

            String json = tool.getDefinition().getInputSchema().toJsonSchema();

            // The schema should include the properties of WrappedType
            assertTrue(json.contains("\"internalId\""), "Schema should contain internalId property: " + json);
            assertTrue(json.contains("\"name\""), "Schema should contain name property: " + json);
            assertTrue(json.contains("\"integer\""), "internalId should have integer type: " + json);
        }

        @Test
        void schemaForAddressParameterShouldIncludeAllAddressProperties() {
            ComplexParameterTools tools = new ComplexParameterTools();
            List<Tool> result = Tool.fromInstance(tools);

            Tool tool = result.stream()
                .filter(t -> t.getDefinition().getName().equals("processAddress"))
                .findFirst()
                .orElseThrow();

            String json = tool.getDefinition().getInputSchema().toJsonSchema();

            assertTrue(json.contains("\"street\""), "Schema should contain street property: " + json);
            assertTrue(json.contains("\"city\""), "Schema should contain city property: " + json);
            assertTrue(json.contains("\"zipCode\""), "Schema should contain zipCode property: " + json);
        }

        @Test
        void schemaWithMixedSimpleAndComplexParamsShouldHandleBothCorrectly() {
            ComplexParameterTools tools = new ComplexParameterTools();
            List<Tool> result = Tool.fromInstance(tools);

            Tool tool = result.stream()
                .filter(t -> t.getDefinition().getName().equals("mixedParams"))
                .findFirst()
                .orElseThrow();

            String json = tool.getDefinition().getInputSchema().toJsonSchema();

            // Simple params should be present at top level
            assertTrue(json.contains("\"label\""), "Schema should contain label param: " + json);
            assertTrue(json.contains("\"count\""), "Schema should contain count param: " + json);
            // Complex type's properties should also be present (nested under wrapped)
            assertTrue(json.contains("\"internalId\""), "Schema should contain wrapped type's internalId: " + json);
        }

        @Test
        void executionWithComplexTypeParameterShouldWorkCorrectly() {
            ComplexParameterTools tools = new ComplexParameterTools();
            List<Tool> result = Tool.fromInstance(tools);

            Tool tool = result.stream()
                .filter(t -> t.getDefinition().getName().equals("getInformation"))
                .findFirst()
                .orElseThrow();

            Tool.Result toolResult = tool.call("{\"someType\": {\"internalId\": 42, \"name\": \"Test\"}}");

            assertInstanceOf(Tool.Result.Text.class, toolResult);
            assertEquals("ID: 42, Name: Test", ((Tool.Result.Text) toolResult).getContent());
        }
    }
}
