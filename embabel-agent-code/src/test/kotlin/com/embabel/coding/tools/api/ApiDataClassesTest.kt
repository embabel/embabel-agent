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
package com.embabel.coding.tools.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ApiDataClassesTest {

    @Test
    fun `should create ApiMethod with required fields`() {
        // Arrange & Act
        val method = ApiMethod(
            name = "testMethod",
            parameters = listOf("String arg1", "int arg2"),
            returnType = "void"
        )

        // Assert
        assertEquals("testMethod", method.name)
        assertEquals(listOf("String arg1", "int arg2"), method.parameters)
        assertEquals("void", method.returnType)
        assertTrue(method.annotations.isEmpty())
        assertNull(method.comment)
    }

    @Test
    fun `should create ApiMethod with all fields`() {
        // Arrange & Act
        val method = ApiMethod(
            name = "annotatedMethod",
            parameters = listOf("String input"),
            returnType = "String",
            annotations = listOf("@Override", "@Deprecated"),
            comment = "This is a test method"
        )

        // Assert
        assertEquals("annotatedMethod", method.name)
        assertEquals(listOf("String input"), method.parameters)
        assertEquals("String", method.returnType)
        assertEquals(listOf("@Override", "@Deprecated"), method.annotations)
        assertEquals("This is a test method", method.comment)
    }

    @Test
    fun `should create ApiClass with required fields`() {
        // Arrange & Act
        val apiClass = ApiClass(
            name = "TestClass",
            packageName = "com.example",
            type = "class"
        )

        // Assert
        assertEquals("TestClass", apiClass.name)
        assertEquals("com.example", apiClass.packageName)
        assertEquals("class", apiClass.type)
        assertTrue(apiClass.methods.isEmpty())
        assertTrue(apiClass.annotations.isEmpty())
        assertTrue(apiClass.superTypes.isEmpty())
        assertNull(apiClass.comment)
    }

    @Test
    fun `should create ApiClass with all fields`() {
        // Arrange
        val method1 = ApiMethod("method1", emptyList(), "void")
        val method2 = ApiMethod("method2", listOf("int x"), "String")

        // Act
        val apiClass = ApiClass(
            name = "CompleteClass",
            packageName = "com.complete",
            type = "interface",
            methods = listOf(method1, method2),
            annotations = listOf("@FunctionalInterface"),
            superTypes = listOf("Serializable", "Comparable"),
            comment = "Complete class javadoc"
        )

        // Assert
        assertEquals("CompleteClass", apiClass.name)
        assertEquals("com.complete", apiClass.packageName)
        assertEquals("interface", apiClass.type)
        assertEquals(2, apiClass.methods.size)
        assertEquals(listOf("@FunctionalInterface"), apiClass.annotations)
        assertEquals(listOf("Serializable", "Comparable"), apiClass.superTypes)
        assertEquals("Complete class javadoc", apiClass.comment)
    }

    @Test
    fun `should generate fully qualified name`() {
        // Arrange
        val apiClass = ApiClass(
            name = "MyClass",
            packageName = "org.example.test",
            type = "class"
        )

        // Act
        val fqn = apiClass.fqn()

        // Assert
        assertEquals("org.example.test.MyClass", fqn)
    }

    @Test
    fun `should create Api with name and classes`() {
        // Arrange
        val class1 = ApiClass("Class1", "com.api", "class")
        val class2 = ApiClass("Class2", "com.api", "interface")

        // Act
        val api = Api(
            name = "TestAPI",
            classes = listOf(class1, class2),
            totalClasses = 10,
            totalMethods = 50
        )

        // Assert
        assertEquals("TestAPI", api.name)
        assertEquals(2, api.classes.size)
        assertEquals(10, api.totalClasses)
        assertEquals(50, api.totalMethods)
    }

    @Test
    fun `should implement Named interface`() {
        // Arrange
        val api = Api(
            name = "NamedAPI",
            classes = emptyList(),
            totalClasses = 0,
            totalMethods = 0
        )

        // Assert
        assertTrue(api is com.embabel.common.core.types.Named)
        assertEquals("NamedAPI", api.name)
    }

    @Test
    fun `should handle empty methods in ApiClass`() {
        // Arrange & Act
        val apiClass = ApiClass(
            name = "EmptyClass",
            packageName = "com.empty",
            type = "class",
            methods = emptyList()
        )

        // Assert
        assertTrue(apiClass.methods.isEmpty())
    }

    @Test
    fun `should handle empty parameters in ApiMethod`() {
        // Arrange & Act
        val method = ApiMethod(
            name = "noParams",
            parameters = emptyList(),
            returnType = "void"
        )

        // Assert
        assertTrue(method.parameters.isEmpty())
    }

    @Test
    fun `should support different class types`() {
        // Arrange & Act
        val classType = ApiClass("MyClass", "pkg", "class")
        val interfaceType = ApiClass("MyInterface", "pkg", "interface")
        val enumType = ApiClass("MyEnum", "pkg", "enum")
        val annotationType = ApiClass("MyAnnotation", "pkg", "annotation")

        // Assert
        assertEquals("class", classType.type)
        assertEquals("interface", interfaceType.type)
        assertEquals("enum", enumType.type)
        assertEquals("annotation", annotationType.type)
    }
}
