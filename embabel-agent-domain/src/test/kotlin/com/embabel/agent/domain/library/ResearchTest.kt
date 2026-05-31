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

class ResearchTest {

    @Test
    fun `should create research topic with topic and questions`() {
        // Arrange
        val questions = listOf("What is AI?", "How does it work?")

        // Act
        val topic = ResearchTopic("Artificial Intelligence", questions)

        // Assert
        assertEquals("Artificial Intelligence", topic.topic)
        assertEquals(questions, topic.questions)
    }

    @Test
    fun `should have meaningful toString for research topic`() {
        // Arrange
        val questions = listOf("Question 1", "Question 2")
        val topic = ResearchTopic("Test Topic", questions)

        // Act
        val string = topic.toString()

        // Assert
        assertTrue(string.contains("ResearchTopic"))
        assertTrue(string.contains("Test Topic"))
        assertTrue(string.contains("questions"))
    }

    @Test
    fun `should create research topics with list of topics`() {
        // Arrange
        val topic1 = ResearchTopic("Topic 1", listOf("Q1"))
        val topic2 = ResearchTopic("Topic 2", listOf("Q2"))
        val topicList = listOf(topic1, topic2)

        // Act
        val topics = ResearchTopics(topicList)

        // Assert
        assertEquals(topicList, topics.topics)
        assertEquals(2, topics.topics.size)
    }

    @Test
    fun `should create research report with topic content and links`() {
        // Arrange
        val link = InternetResource("https://example.com", "Example summary")
        val links = listOf(link)

        // Act
        val report = ResearchReport("AI Ethics", "Research content here", links)

        // Assert
        assertEquals("AI Ethics", report.topic)
        assertEquals("Research content here", report.content)
        assertEquals(links, report.links)
        assertNotNull(report.timestamp)
    }

    @Test
    fun `should implement ContentAsset interface`() {
        // Arrange
        val report = ResearchReport("Topic", "Content", emptyList())

        // Assert
        assertTrue(report is ContentAsset)
        assertEquals("Content", report.content)
        assertNotNull(report.timestamp)
    }

    @Test
    fun `should generate contribution with correct format`() {
        // Arrange
        val link = InternetResource("https://test.com", "Test summary")
        val report = ResearchReport("Test Topic", "Test content", listOf(link))

        // Act
        val contribution = report.contribution()

        // Assert
        assertTrue(contribution.contains("Research Report:"))
        assertTrue(contribution.contains("Topic: Test Topic"))
        assertTrue(contribution.contains("Content: Test content"))
        assertTrue(contribution.contains("https://test.com - Test summary"))
        assertTrue(contribution.contains("Date:"))
    }

    @Test
    fun `should generate info string with verbose details`() {
        // Arrange
        val link = InternetResource("https://info.com", "Info summary")
        val report = ResearchReport("Info Topic", "Info content", listOf(link))

        // Act
        val infoString = report.infoString(verbose = true, indent = 0)

        // Assert
        assertTrue(infoString.contains("Report:"))
        assertTrue(infoString.contains("Info content"))
        assertTrue(infoString.contains("Links:"))
        assertTrue(infoString.contains("https://info.com"))
    }

    @Test
    fun `should generate toString representation`() {
        // Arrange
        val report = ResearchReport("String Topic", "String content", emptyList())

        // Act
        val string = report.toString()

        // Assert
        assertTrue(string.contains("Report:"))
        assertTrue(string.contains("String content"))
    }
}
