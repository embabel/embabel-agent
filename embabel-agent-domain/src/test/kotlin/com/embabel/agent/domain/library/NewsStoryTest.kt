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

class NewsStoryTest {

    @Test
    fun `should create news story with url title and summary`() {
        // Arrange & Act
        val story = NewsStory(
            url = "https://news.example.com/story",
            title = "Breaking News",
            summary = "Important event occurred"
        )

        // Assert
        assertEquals("https://news.example.com/story", story.url)
        assertEquals("Breaking News", story.title)
        assertEquals("Important event occurred", story.summary)
    }

    @Test
    fun `should implement Page interface`() {
        // Arrange
        val story = NewsStory("https://test.com", "Test", "Summary")

        // Assert
        assertTrue(story is Page)
        assertEquals("https://test.com", story.url)
        assertEquals("Summary", story.summary)
    }

    @Test
    fun `should generate contribution with correct format`() {
        // Arrange
        val story = NewsStory(
            url = "https://example.com",
            title = "Test Title",
            summary = "Test Summary"
        )

        // Act
        val contribution = story.contribution()

        // Assert
        assertTrue(contribution.contains("Title: Test Title"))
        assertTrue(contribution.contains("Summary: Test Summary"))
        assertTrue(contribution.contains("URL: https://example.com"))
    }

    @Test
    fun `should create relevant news stories with list of stories`() {
        // Arrange
        val story1 = NewsStory("https://news1.com", "Story 1", "Summary 1")
        val story2 = NewsStory("https://news2.com", "Story 2", "Summary 2")
        val stories = listOf(story1, story2)

        // Act
        val relevantStories = RelevantNewsStories(stories)

        // Assert
        assertEquals(stories, relevantStories.items)
        assertEquals(2, relevantStories.items.size)
    }

    @Test
    fun `should generate contribution from multiple stories`() {
        // Arrange
        val story1 = NewsStory("https://news1.com", "Title 1", "Summary 1")
        val story2 = NewsStory("https://news2.com", "Title 2", "Summary 2")
        val relevantStories = RelevantNewsStories(listOf(story1, story2))

        // Act
        val contribution = relevantStories.contribution()

        // Assert
        assertTrue(contribution.contains("Title 1"))
        assertTrue(contribution.contains("Summary 1"))
        assertTrue(contribution.contains("Title 2"))
        assertTrue(contribution.contains("Summary 2"))
    }

    @Test
    fun `should return message when no stories found`() {
        // Arrange
        val relevantStories = RelevantNewsStories(emptyList())

        // Act
        val contribution = relevantStories.contribution()

        // Assert
        assertEquals("No relevant news stories found.", contribution)
    }

    @Test
    fun `should implement PromptContributor interface`() {
        // Arrange
        val story = NewsStory("https://test.com", "Test", "Test Summary")
        val relevantStories = RelevantNewsStories(listOf(story))

        // Assert
        assertTrue(story is com.embabel.common.ai.prompt.PromptContributor)
        assertTrue(relevantStories is com.embabel.common.ai.prompt.PromptContributor)
        assertNotNull(story.contribution())
        assertNotNull(relevantStories.contribution())
    }
}
