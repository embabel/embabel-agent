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
package com.embabel.agent.config.models.ollama

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OllamaNodePropertiesTest {

    @Test
    fun `should create OllamaNodeProperties with default empty nodes`() {
        // Arrange & Act
        val properties = OllamaNodeProperties()

        // Assert
        assertTrue(properties.nodes.isEmpty())
    }

    @Test
    fun `should create OllamaNodeProperties with nodes`() {
        // Arrange
        val node1 = OllamaNodeConfig(name = "main", baseUrl = "http://localhost:11434")
        val node2 = OllamaNodeConfig(name = "gpu-server", baseUrl = "http://localhost:11435")

        // Act
        val properties = OllamaNodeProperties(nodes = listOf(node1, node2))

        // Assert
        assertEquals(2, properties.nodes.size)
        assertEquals("main", properties.nodes[0].name)
        assertEquals("http://localhost:11434", properties.nodes[0].baseUrl)
        assertEquals("gpu-server", properties.nodes[1].name)
        assertEquals("http://localhost:11435", properties.nodes[1].baseUrl)
    }

    @Test
    fun `should set nodes after creation`() {
        // Arrange
        val properties = OllamaNodeProperties()
        val node = OllamaNodeConfig(name = "test", baseUrl = "http://test:11434")

        // Act
        properties.nodes = listOf(node)

        // Assert
        assertEquals(1, properties.nodes.size)
        assertEquals("test", properties.nodes[0].name)
    }

    @Test
    fun `should support copy with modified nodes`() {
        // Arrange
        val original = OllamaNodeProperties(nodes = emptyList())
        val node = OllamaNodeConfig(name = "new", baseUrl = "http://new:11434")

        // Act
        val modified = original.copy(nodes = listOf(node))

        // Assert
        assertEquals(1, modified.nodes.size)
        assertTrue(original.nodes.isEmpty())
    }

    @Test
    fun `should support equality for OllamaNodeProperties`() {
        // Arrange
        val node = OllamaNodeConfig(name = "test", baseUrl = "http://test:11434")
        val props1 = OllamaNodeProperties(nodes = listOf(node))
        val props2 = OllamaNodeProperties(nodes = listOf(node))
        val props3 = OllamaNodeProperties(nodes = emptyList())

        // Assert
        assertEquals(props1, props2)
        assertNotEquals(props1, props3)
    }

    @Test
    fun `should create OllamaNodeConfig with default values`() {
        // Arrange & Act
        val config = OllamaNodeConfig()

        // Assert
        assertEquals("", config.name)
        assertEquals("", config.baseUrl)
    }

    @Test
    fun `should create OllamaNodeConfig with custom values`() {
        // Arrange & Act
        val config = OllamaNodeConfig(
            name = "production",
            baseUrl = "https://ollama.example.com"
        )

        // Assert
        assertEquals("production", config.name)
        assertEquals("https://ollama.example.com", config.baseUrl)
    }

    @Test
    fun `should set name and baseUrl`() {
        // Arrange
        val config = OllamaNodeConfig()

        // Act
        config.name = "dev"
        config.baseUrl = "http://localhost:11434"

        // Assert
        assertEquals("dev", config.name)
        assertEquals("http://localhost:11434", config.baseUrl)
    }

    @Test
    fun `should support copy for OllamaNodeConfig`() {
        // Arrange
        val original = OllamaNodeConfig(name = "original", baseUrl = "http://original:11434")

        // Act
        val modified = original.copy(name = "modified")

        // Assert
        assertEquals("modified", modified.name)
        assertEquals("http://original:11434", modified.baseUrl)
        assertEquals("original", original.name)
    }

    @Test
    fun `should support equality for OllamaNodeConfig`() {
        // Arrange
        val config1 = OllamaNodeConfig(name = "test", baseUrl = "http://test:11434")
        val config2 = OllamaNodeConfig(name = "test", baseUrl = "http://test:11434")
        val config3 = OllamaNodeConfig(name = "different", baseUrl = "http://test:11434")

        // Assert
        assertEquals(config1, config2)
        assertNotEquals(config1, config3)
    }
}
