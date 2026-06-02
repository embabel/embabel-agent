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
package com.embabel.coding.tools.jvm

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MavenBuildSystemIntegrationTest {

    private val integration = MavenBuildSystemIntegration()

    @Test
    fun `should return null for non-Maven output`() {
        // Arrange
        val output = """
            Gradle build output
            BUILD SUCCESSFUL
        """.trimIndent()

        // Act
        val result = integration.parseBuildOutput("/root", output)

        // Assert
        assertNull(result)
    }

    @Test
    fun `should return null for empty output`() {
        // Arrange
        val output = ""

        // Act
        val result = integration.parseBuildOutput("/root", output)

        // Assert
        assertNull(result)
    }

    @Test
    fun `should parse successful build with no warnings or errors`() {
        // Arrange
        val output = """
            [INFO] Scanning for projects...
            [INFO] Building project 1.0.0
            [INFO] BUILD SUCCESS
        """.trimIndent()

        // Act
        val result = integration.parseBuildOutput("/root", output)

        // Assert
        assertNotNull(result)
        assertTrue(result!!.success)
        assertEquals("\n", result.relevantOutput)
    }

    @Test
    fun `should parse successful build with warnings`() {
        // Arrange
        val output = """
            [INFO] Scanning for projects...
            [WARNING] Some deprecated API usage
            [INFO] Building project 1.0.0
            [WARNING] Another warning here
            [INFO] BUILD SUCCESS
        """.trimIndent()

        // Act
        val result = integration.parseBuildOutput("/root", output)

        // Assert
        assertNotNull(result)
        assertTrue(result!!.success)
        assertTrue(result.relevantOutput.contains("[WARNING] Some deprecated API usage"))
        assertTrue(result.relevantOutput.contains("[WARNING] Another warning here"))
    }

    @Test
    fun `should parse failed build with errors`() {
        // Arrange
        val output = """
            [INFO] Scanning for projects...
            [INFO] Building project 1.0.0
            [ERROR] Compilation failure
            [ERROR] Cannot find symbol
            [INFO] BUILD FAILURE
        """.trimIndent()

        // Act
        val result = integration.parseBuildOutput("/root", output)

        // Assert
        assertNotNull(result)
        assertFalse(result!!.success)
        assertTrue(result.relevantOutput.contains("[ERROR] Compilation failure"))
        assertTrue(result.relevantOutput.contains("[ERROR] Cannot find symbol"))
    }

    @Test
    fun `should parse build with both warnings and errors`() {
        // Arrange
        val output = """
            [INFO] Scanning for projects...
            [WARNING] Deprecated API used
            [INFO] Building project 1.0.0
            [ERROR] Test failures
            [WARNING] Unchecked conversion
            [ERROR] NullPointerException in test
            [INFO] BUILD FAILURE
        """.trimIndent()

        // Act
        val result = integration.parseBuildOutput("/root", output)

        // Assert
        assertNotNull(result)
        assertFalse(result!!.success)
        assertTrue(result.relevantOutput.contains("[WARNING] Deprecated API used"))
        assertTrue(result.relevantOutput.contains("[WARNING] Unchecked conversion"))
        assertTrue(result.relevantOutput.contains("[ERROR] Test failures"))
        assertTrue(result.relevantOutput.contains("[ERROR] NullPointerException in test"))
    }

    @Test
    fun `should handle Maven output without BUILD SUCCESS or FAILURE`() {
        // Arrange
        val output = """
            [INFO] Scanning for projects...
            [INFO] Building project 1.0.0
            [INFO] Compiling sources
        """.trimIndent()

        // Act
        val result = integration.parseBuildOutput("/root", output)

        // Assert
        assertNotNull(result)
        assertFalse(result!!.success)
    }

    @Test
    fun `should extract only lines containing WARNING tag`() {
        // Arrange
        val output = """
            [INFO] Start build
            [WARNING] First warning
            Some warning without tag
            [WARNING] Second warning
            [INFO] BUILD SUCCESS
        """.trimIndent()

        // Act
        val result = integration.parseBuildOutput("/root", output)

        // Assert
        assertNotNull(result)
        val warnings = result!!.relevantOutput.lines().filter { it.contains("[WARNING]") }
        assertEquals(2, warnings.size)
        assertFalse(result.relevantOutput.contains("Some warning without tag"))
    }

    @Test
    fun `should extract only lines containing ERROR tag`() {
        // Arrange
        val output = """
            [INFO] Start build
            [ERROR] First error
            Some error without tag
            [ERROR] Second error
            [INFO] BUILD FAILURE
        """.trimIndent()

        // Act
        val result = integration.parseBuildOutput("/root", output)

        // Assert
        assertNotNull(result)
        val errors = result!!.relevantOutput.lines().filter { it.contains("[ERROR]") }
        assertEquals(2, errors.size)
        assertFalse(result.relevantOutput.contains("Some error without tag"))
    }

    @Test
    fun `should ignore root parameter`() {
        // Arrange
        val output = """
            [INFO] Building
            [INFO] BUILD SUCCESS
        """.trimIndent()

        // Act
        val result1 = integration.parseBuildOutput("/path/one", output)
        val result2 = integration.parseBuildOutput("/path/two", output)

        // Assert
        assertNotNull(result1)
        assertNotNull(result2)
        assertEquals(result1!!.success, result2!!.success)
        assertEquals(result1.relevantOutput, result2.relevantOutput)
    }

    @Test
    fun `should handle multiline error messages`() {
        // Arrange
        val output = """
            [INFO] Building
            [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin
            [ERROR] Compilation error on line 42
            [ERROR] Expected ';' but found '}'
            [INFO] BUILD FAILURE
        """.trimIndent()

        // Act
        val result = integration.parseBuildOutput("/root", output)

        // Assert
        assertNotNull(result)
        assertFalse(result!!.success)
        val errorLines = result.relevantOutput.lines().filter { it.contains("[ERROR]") }
        assertEquals(3, errorLines.size)
    }

    @Test
    fun `should implement BuildSystemIntegration interface`() {
        // Assert
        assertTrue(integration is com.embabel.coding.tools.ci.BuildSystemIntegration)
    }
}
