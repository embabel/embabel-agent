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
package com.embabel.agent.domain.library.code

import com.embabel.agent.api.common.Asyncer
import com.embabel.agent.tools.file.PatternSearch
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SymbolSearchTest {

    private class TestSymbolSearch : SymbolSearch {
        override val root: String = "/test/root"
        var lastPattern: Regex? = null
        var lastGlobPattern: String? = null
        var lastAsyncer: Asyncer? = null

        override fun findPatternInProject(
            pattern: Regex,
            globPattern: String,
            asyncer: Asyncer?
        ): List<PatternSearch.PatternMatch> {
            lastPattern = pattern
            lastGlobPattern = globPattern
            lastAsyncer = asyncer
            return emptyList()
        }
    }

    @Test
    fun `classPattern should match class declarations`() {
        // Arrange
        val symbolSearch = TestSymbolSearch()

        // Act
        val pattern = symbolSearch.classPattern("MyClass")

        // Assert
        assertTrue(pattern.containsMatchIn("class MyClass {"))
        assertTrue(pattern.containsMatchIn("class MyClass("))
        assertTrue(pattern.containsMatchIn("class MyClass<T>"))
        assertTrue(pattern.containsMatchIn("  class MyClass {"))
    }

    @Test
    fun `classPattern should match interface declarations`() {
        // Arrange
        val symbolSearch = TestSymbolSearch()

        // Act
        val pattern = symbolSearch.classPattern("MyInterface")

        // Assert
        assertTrue(pattern.containsMatchIn("interface MyInterface {"))
        assertTrue(pattern.containsMatchIn("interface MyInterface<T>"))
    }

    @Test
    fun `classPattern should match object declarations`() {
        // Arrange
        val symbolSearch = TestSymbolSearch()

        // Act
        val pattern = symbolSearch.classPattern("MyObject")

        // Assert
        assertTrue(pattern.containsMatchIn("object MyObject {"))
        assertTrue(pattern.containsMatchIn("object MyObject "))
    }

    @Test
    fun `classPattern should match data class declarations`() {
        // Arrange
        val symbolSearch = TestSymbolSearch()

        // Act
        val pattern = symbolSearch.classPattern("MyDataClass")

        // Assert
        assertTrue(pattern.containsMatchIn("data class MyDataClass("))
        assertTrue(pattern.containsMatchIn("data class MyDataClass {"))
    }

    @Test
    fun `classPattern should match sealed class declarations`() {
        // Arrange
        val symbolSearch = TestSymbolSearch()

        // Act
        val pattern = symbolSearch.classPattern("MySealed")

        // Assert
        assertTrue(pattern.containsMatchIn("sealed class MySealed {"))
        assertTrue(pattern.containsMatchIn("sealed class MySealed("))
    }

    @Test
    fun `classPattern should match abstract class declarations`() {
        // Arrange
        val symbolSearch = TestSymbolSearch()

        // Act
        val pattern = symbolSearch.classPattern("MyAbstract")

        // Assert
        assertTrue(pattern.containsMatchIn("abstract class MyAbstract {"))
        assertTrue(pattern.containsMatchIn("abstract class MyAbstract("))
    }

    @Test
    fun `classPattern should match enum class declarations`() {
        // Arrange
        val symbolSearch = TestSymbolSearch()

        // Act
        val pattern = symbolSearch.classPattern("MyEnum")

        // Assert
        assertTrue(pattern.containsMatchIn("enum class MyEnum {"))
    }

    @Test
    fun `classPattern should not match partial class names`() {
        // Arrange
        val symbolSearch = TestSymbolSearch()

        // Act
        val pattern = symbolSearch.classPattern("Class")

        // Assert
        assertFalse(pattern.containsMatchIn("class MyClass {"))
        assertFalse(pattern.containsMatchIn("class ClassHelper {"))
    }

    @Test
    fun `classPattern should match at word boundary`() {
        // Arrange
        val symbolSearch = TestSymbolSearch()

        // Act
        val pattern = symbolSearch.classPattern("Test")

        // Assert
        assertTrue(pattern.containsMatchIn("class Test {"))
        assertFalse(pattern.containsMatchIn("class TestClass {"))
        assertFalse(pattern.containsMatchIn("class MyTest {"))
    }

    @Test
    fun `findClassInProject should use default glob pattern`() {
        // Arrange
        val symbolSearch = TestSymbolSearch()

        // Act
        symbolSearch.findClassInProject("MyClass")

        // Assert
        assertEquals("**/*.{kt,java}", symbolSearch.lastGlobPattern)
    }

    @Test
    fun `findClassInProject should use custom glob pattern`() {
        // Arrange
        val symbolSearch = TestSymbolSearch()

        // Act
        symbolSearch.findClassInProject("MyClass", "src/**/*.kt")

        // Assert
        assertEquals("src/**/*.kt", symbolSearch.lastGlobPattern)
    }

    @Test
    fun `findClassInProject should pass asyncer`() {
        // Arrange
        val symbolSearch = TestSymbolSearch()
        val mockAsyncer = mockk<Asyncer>()

        // Act
        symbolSearch.findClassInProject("MyClass", asyncer = mockAsyncer)

        // Assert
        assertEquals(mockAsyncer, symbolSearch.lastAsyncer)
    }

    @Test
    fun `findClassInProject should use classPattern`() {
        // Arrange
        val symbolSearch = TestSymbolSearch()

        // Act
        symbolSearch.findClassInProject("MyClass")

        // Assert
        assertNotNull(symbolSearch.lastPattern)
        assertTrue(symbolSearch.lastPattern!!.containsMatchIn("class MyClass {"))
    }

    @Test
    fun `findClassInProject should return pattern matches`() {
        // Arrange
        val symbolSearch = TestSymbolSearch()

        // Act
        val results = symbolSearch.findClassInProject("MyClass")

        // Assert
        assertNotNull(results)
        assertEquals(0, results.size)
    }

    @Test
    fun `classPattern should handle special characters in class name`() {
        // Arrange
        val symbolSearch = TestSymbolSearch()

        // Act
        val pattern = symbolSearch.classPattern("My\$Class")

        // Assert - should not throw exception
        assertNotNull(pattern)
    }
}
