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
}
