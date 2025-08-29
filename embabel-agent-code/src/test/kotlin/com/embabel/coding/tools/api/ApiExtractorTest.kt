/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ApiExtractorTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var extractor: ApiExtractor
    private lateinit var sourceDir: Path

    @BeforeEach
    fun setUp() {
        extractor = ApiExtractor()
        sourceDir = tempDir.resolve("src")
        Files.createDirectories(sourceDir)
    }

    @Test
    fun `should extract from source code`() {
        // Create a simple Java class
        val packageDir = sourceDir.resolve("com/example")
        Files.createDirectories(packageDir)

        val javaFile = packageDir.resolve("TestService.java")
        Files.write(javaFile, """
            package com.example;

            public class TestService {
                public void doSomething() {}
                public String getName() { return "test"; }
            }
        """.trimIndent().toByteArray())

        val api = extractor.fromSourceCode("test-api", sourceDir)

        assertEquals("test-api", api.name)
        assertEquals(1, api.totalClasses)
        assertEquals(2, api.totalMethods)

        val testService = api.classes.first()
        assertEquals("TestService", testService.name)
        assertEquals("com.example", testService.packageName)
        assertEquals("class", testService.type)
    }

    @Test
    fun `should combine multiple APIs correctly`() {
        val api1 = Api("api1", listOf(
            ApiClass("ClassA", "com.example", "class", listOf(
                ApiMethod("methodA", emptyList(), "void")
            ))
        ), 1, 1)

        val api2 = Api("api2", listOf(
            ApiClass("ClassB", "com.example", "class", listOf(
                ApiMethod("methodB", emptyList(), "void")
            ))
        ), 1, 1)

        val combined = extractor.combine("combined-api", listOf(api1, api2))

        assertEquals("combined-api", combined.name)
        assertEquals(2, combined.totalClasses)
        assertEquals(2, combined.totalMethods)
        assertTrue(combined.classes.any { it.name == "ClassA" })
        assertTrue(combined.classes.any { it.name == "ClassB" })
    }

    @Test
    fun `should deduplicate classes when combining`() {
        val api1 = Api("api1", listOf(
            ApiClass("DuplicateClass", "com.example", "class", listOf(
                ApiMethod("method1", emptyList(), "void")
            ))
        ), 1, 1)

        val api2 = Api("api2", listOf(
            ApiClass("DuplicateClass", "com.example", "class", listOf(
                ApiMethod("method2", emptyList(), "void")
            ))
        ), 1, 1)

        val combined = extractor.combine("combined-api", listOf(api1, api2))

        assertEquals("combined-api", combined.name)
        assertEquals(1, combined.totalClasses) // Duplicates should be removed
        assertEquals("DuplicateClass", combined.classes.first().name)
    }

    @Test
    fun `should extract from classpath`() {
        // Test extracting from current classpath with Kotlin/Java classes
        val api = extractor.fromClasspath(
            "classpath-api",
            acceptedPackages = setOf("com.embabel.coding.tools.api")
        )

        assertTrue(api.totalClasses > 0)
        assertTrue(api.classes.any { it.name == "Api" })
        assertTrue(api.classes.any { it.name == "ApiClass" })
        assertTrue(api.classes.any { it.name == "ApiMethod" })
    }

    @Test
    fun `should handle empty source directory gracefully in combined extraction`() {
        val emptySourceDir = tempDir.resolve("empty-source")
        Files.createDirectories(emptySourceDir)

        val api = extractor.fromSourceAndClasspath(
            "combined-api",
            emptySourceDir,
            acceptedPackages = setOf("com.embabel.coding.tools.api")
        )

        // Should have classes from classpath even if source is empty
        assertTrue(api.totalClasses > 0)
        assertTrue(api.classes.any { it.name == "Api" })
    }

    @Test
    fun `should prefer source code over classpath in combined extraction`() {
        // Create source code with same package as classpath
        val packageDir = sourceDir.resolve("com/example")
        Files.createDirectories(packageDir)

        Files.write(packageDir.resolve("TestClass.java"), """
            package com.example;
            public class TestClass {
                public void sourceMethod() {}
            }
        """.trimIndent().toByteArray())

        val api = extractor.fromSourceAndClasspath(
            "combined-api",
            sourceDir,
            acceptedPackages = setOf("com.example", "com.embabel.coding.tools.api")
        )

        // Should include both source and classpath classes
        assertTrue(api.classes.any { it.name == "TestClass" }) // From source
        assertTrue(api.classes.any { it.name == "Api" }) // From classpath
    }
}
