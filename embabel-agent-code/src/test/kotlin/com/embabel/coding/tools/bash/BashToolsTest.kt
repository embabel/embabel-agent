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
package com.embabel.coding.tools.bash

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@EnabledOnOs(OS.LINUX, OS.MAC)
class BashToolsTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `should execute simple bash command`() {
        // Arrange
        val bashTools = BashTools(tempDir.toString())

        // Act
        val result = bashTools.runBashCommand("echo 'Hello World'")

        // Assert
        assertTrue(result.contains("Hello World"))
    }

    @Test
    fun `should execute command in specified working directory`() {
        // Arrange
        val bashTools = BashTools(tempDir.toString())

        // Act
        val result = bashTools.runBashCommand("pwd")

        // Assert
        assertTrue(result.trim().endsWith(tempDir.fileName.toString()))
    }

    @Test
    fun `should capture command output`() {
        // Arrange
        val bashTools = BashTools(tempDir.toString())

        // Act
        val result = bashTools.runBashCommand("echo 'line1'; echo 'line2'")

        // Assert
        assertTrue(result.contains("line1"))
        assertTrue(result.contains("line2"))
    }
}
