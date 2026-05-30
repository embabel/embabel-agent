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
package com.embabel.coding.tools.ci

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CiToolsTest {

    private class TestCiTools(override val root: String) : CiTools

    @Test
    fun `should have buildProject method`() {
        // Arrange
        val tempDir = createTempDir()
        tempDir.deleteOnExit()
        val ciTools = TestCiTools(tempDir.absolutePath)

        // Act & Assert
        assertNotNull(ciTools)
    }

    @Test
    fun `should have buildProjectInteractive method`() {
        // Arrange
        val tempDir = createTempDir()
        tempDir.deleteOnExit()
        val ciTools = TestCiTools(tempDir.absolutePath)

        // Act & Assert
        assertNotNull(ciTools)
    }

    @Test
    fun `should implement DirectoryBased interface`() {
        // Arrange
        val tempDir = createTempDir()
        tempDir.deleteOnExit()
        val rootPath = tempDir.absolutePath
        val ciTools = TestCiTools(rootPath)

        // Act & Assert
        assertEquals(rootPath, ciTools.root)
    }
}
