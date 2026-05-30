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
package com.embabel.agent.domain.library

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class BlogTest {

    @Test
    fun `should create blog with required fields`() {
        // Arrange & Act
        val blog = Blog(
            title = "Test Blog",
            author = "Test Author",
            content = "Test content"
        )

        // Assert
        assertEquals("Test Blog", blog.title)
        assertEquals("Test Author", blog.author)
        assertEquals("Test content", blog.content)
        assertNotNull(blog.timestamp)
        assertTrue(blog.keywords.isEmpty())
        assertEquals("markdown", blog.format)
    }

    @Test
    fun `should create blog with all fields`() {
        // Arrange
        val timestamp = Instant.now()
        val keywords = setOf("kotlin", "testing")

        // Act
        val blog = Blog(
            title = "Complete Blog",
            author = "Full Author",
            content = "Full content",
            timestamp = timestamp,
            keywords = keywords,
            format = "html"
        )

        // Assert
        assertEquals("Complete Blog", blog.title)
        assertEquals("Full Author", blog.author)
        assertEquals("Full content", blog.content)
        assertEquals(timestamp, blog.timestamp)
        assertEquals(keywords, blog.keywords)
        assertEquals("html", blog.format)
    }

    @Test
    fun `should generate contribution with correct format`() {
        // Arrange
        val blog = Blog(
            title = "AI Blog",
            author = "Claude",
            content = "AI is fascinating"
        )

        // Act
        val contribution = blog.contribution()

        // Assert
        assertTrue(contribution.contains("Blog Post:"))
        assertTrue(contribution.contains("Title: AI Blog"))
        assertTrue(contribution.contains("Author: Claude"))
        assertTrue(contribution.contains("Content: AI is fascinating"))
        assertTrue(contribution.contains("Date:"))
    }

    @Test
    fun `should include timestamp in contribution`() {
        // Arrange
        val timestamp = Instant.parse("2024-01-15T10:30:00Z")
        val blog = Blog(
            title = "Test",
            author = "Test",
            content = "Test",
            timestamp = timestamp
        )

        // Act
        val contribution = blog.contribution()

        // Assert
        assertTrue(contribution.contains("Date:"))
    }

    @Test
    fun `should implement ContentAsset interface`() {
        // Arrange
        val blog = Blog(
            title = "Test",
            author = "Test",
            content = "Test content"
        )

        // Assert
        assertTrue(blog is ContentAsset)
        assertEquals("Test content", blog.content)
        assertNotNull(blog.timestamp)
    }
}
