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
package com.embabel.agent.rag.pipeline.event

import com.embabel.agent.rag.service.RagRequest
import com.embabel.agent.rag.service.RagResponse
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class RagPipelineEventTest {

    @Test
    fun `InitialRequestRagPipelineEvent should be created with request and service`() {
        // Arrange
        val mockRequest = mockk<RagRequest>()
        val service = "test-service"

        // Act
        val event = InitialRequestRagPipelineEvent(
            request = mockRequest,
            service = service
        )

        // Assert
        assertEquals(mockRequest, event.request)
        assertEquals(service, event.service)
        assertEquals("Initial RAG request to test-service", event.description)
        assertNotNull(event.timestamp)
    }

    @Test
    fun `InitialRequestRagPipelineEvent description should include service name`() {
        // Arrange
        val mockRequest = mockk<RagRequest>()

        // Act
        val event = InitialRequestRagPipelineEvent(
            request = mockRequest,
            service = "my-rag-service"
        )

        // Assert
        assertTrue(event.description.contains("my-rag-service"))
    }

    @Test
    fun `InitialRequestRagPipelineEvent should extend RagPipelineEvent`() {
        // Arrange
        val mockRequest = mockk<RagRequest>()

        // Act
        val event = InitialRequestRagPipelineEvent(mockRequest, "service")

        // Assert
        assertTrue(event is RagPipelineEvent)
    }

    @Test
    fun `InitialResponseRagPipelineEvent should be created with response and service`() {
        // Arrange
        val mockResponse = mockk<RagResponse>()
        val mockRequest = mockk<RagRequest>()
        every { mockResponse.request } returns mockRequest
        val service = "response-service"

        // Act
        val event = InitialResponseRagPipelineEvent(
            response = mockResponse,
            service = service
        )

        // Assert
        assertEquals(mockResponse, event.response)
        assertEquals(service, event.service)
        assertEquals("Initial RAG response from response-service", event.description)
        assertNotNull(event.timestamp)
    }

    @Test
    fun `InitialResponseRagPipelineEvent description should include service name`() {
        // Arrange
        val mockResponse = mockk<RagResponse>()
        val mockRequest = mockk<RagRequest>()
        every { mockResponse.request } returns mockRequest

        // Act
        val event = InitialResponseRagPipelineEvent(
            response = mockResponse,
            service = "response-handler"
        )

        // Assert
        assertTrue(event.description.contains("response-handler"))
    }

    @Test
    fun `InitialResponseRagPipelineEvent request should come from response`() {
        // Arrange
        val mockResponse = mockk<RagResponse>()
        val mockRequest = mockk<RagRequest>()
        every { mockResponse.request } returns mockRequest

        // Act
        val event = InitialResponseRagPipelineEvent(mockResponse, "service")

        // Assert
        assertEquals(mockRequest, event.request)
    }

    @Test
    fun `InitialResponseRagPipelineEvent should extend RagPipelineEvent`() {
        // Arrange
        val mockResponse = mockk<RagResponse>()
        val mockRequest = mockk<RagRequest>()
        every { mockResponse.request } returns mockRequest

        // Act
        val event = InitialResponseRagPipelineEvent(mockResponse, "service")

        // Assert
        assertTrue(event is RagPipelineEvent)
    }

    @Test
    fun `EnhancementStartingRagPipelineEvent should be created with basis and enhancerName`() {
        // Arrange
        val mockBasis = mockk<RagResponse>()
        val mockRequest = mockk<RagRequest>()
        every { mockBasis.request } returns mockRequest
        val enhancerName = "compression-enhancer"

        // Act
        val event = EnhancementStartingRagPipelineEvent(
            basis = mockBasis,
            enhancerName = enhancerName
        )

        // Assert
        assertEquals(mockBasis, event.basis)
        assertEquals(enhancerName, event.enhancerName)
        assertEquals("Starting enhancement with compression-enhancer", event.description)
        assertNotNull(event.timestamp)
    }

    @Test
    fun `EnhancementStartingRagPipelineEvent description should include enhancer name`() {
        // Arrange
        val mockBasis = mockk<RagResponse>()
        val mockRequest = mockk<RagRequest>()
        every { mockBasis.request } returns mockRequest

        // Act
        val event = EnhancementStartingRagPipelineEvent(
            basis = mockBasis,
            enhancerName = "reranking-enhancer"
        )

        // Assert
        assertTrue(event.description.contains("reranking-enhancer"))
        assertTrue(event.description.contains("Starting"))
    }

    @Test
    fun `EnhancementStartingRagPipelineEvent request should come from basis`() {
        // Arrange
        val mockBasis = mockk<RagResponse>()
        val mockRequest = mockk<RagRequest>()
        every { mockBasis.request } returns mockRequest

        // Act
        val event = EnhancementStartingRagPipelineEvent(mockBasis, "enhancer")

        // Assert
        assertEquals(mockRequest, event.request)
    }

    @Test
    fun `EnhancementStartingRagPipelineEvent should extend EnhancementRagPipelineEvent`() {
        // Arrange
        val mockBasis = mockk<RagResponse>()
        val mockRequest = mockk<RagRequest>()
        every { mockBasis.request } returns mockRequest

        // Act
        val event = EnhancementStartingRagPipelineEvent(mockBasis, "enhancer")

        // Assert
        assertTrue(event is EnhancementRagPipelineEvent)
        assertTrue(event is RagPipelineEvent)
    }

    @Test
    fun `EnhancementCompletedRagPipelineEvent should be created with response and enhancerName`() {
        // Arrange
        val mockResponse = mockk<RagResponse>()
        val mockRequest = mockk<RagRequest>()
        every { mockResponse.request } returns mockRequest
        val enhancerName = "deduplication-enhancer"

        // Act
        val event = EnhancementCompletedRagPipelineEvent(
            response = mockResponse,
            enhancerName = enhancerName
        )

        // Assert
        assertEquals(mockResponse, event.response)
        assertEquals(enhancerName, event.enhancerName)
        assertEquals("Completed enhancement with deduplication-enhancer", event.description)
        assertNotNull(event.timestamp)
    }

    @Test
    fun `EnhancementCompletedRagPipelineEvent description should include enhancer name`() {
        // Arrange
        val mockResponse = mockk<RagResponse>()
        val mockRequest = mockk<RagRequest>()
        every { mockResponse.request } returns mockRequest

        // Act
        val event = EnhancementCompletedRagPipelineEvent(
            response = mockResponse,
            enhancerName = "quality-enhancer"
        )

        // Assert
        assertTrue(event.description.contains("quality-enhancer"))
        assertTrue(event.description.contains("Completed"))
    }

    @Test
    fun `EnhancementCompletedRagPipelineEvent request should come from response`() {
        // Arrange
        val mockResponse = mockk<RagResponse>()
        val mockRequest = mockk<RagRequest>()
        every { mockResponse.request } returns mockRequest

        // Act
        val event = EnhancementCompletedRagPipelineEvent(mockResponse, "enhancer")

        // Assert
        assertEquals(mockRequest, event.request)
    }

    @Test
    fun `EnhancementCompletedRagPipelineEvent should extend EnhancementRagPipelineEvent`() {
        // Arrange
        val mockResponse = mockk<RagResponse>()
        val mockRequest = mockk<RagRequest>()
        every { mockResponse.request } returns mockRequest

        // Act
        val event = EnhancementCompletedRagPipelineEvent(mockResponse, "enhancer")

        // Assert
        assertTrue(event is EnhancementRagPipelineEvent)
        assertTrue(event is RagPipelineEvent)
    }

    @Test
    fun `all events should have timestamp`() {
        // Arrange
        val mockRequest = mockk<RagRequest>()
        val mockResponse = mockk<RagResponse>()
        every { mockResponse.request } returns mockRequest
        val before = Instant.now()

        // Act
        val event1 = InitialRequestRagPipelineEvent(mockRequest, "service")
        val event2 = InitialResponseRagPipelineEvent(mockResponse, "service")
        val event3 = EnhancementStartingRagPipelineEvent(mockResponse, "enhancer")
        val event4 = EnhancementCompletedRagPipelineEvent(mockResponse, "enhancer")
        val after = Instant.now()

        // Assert
        assertTrue(event1.timestamp.isAfter(before) || event1.timestamp.equals(before))
        assertTrue(event1.timestamp.isBefore(after) || event1.timestamp.equals(after))
        assertNotNull(event2.timestamp)
        assertNotNull(event3.timestamp)
        assertNotNull(event4.timestamp)
    }

    @Test
    fun `events should handle special characters in service names`() {
        // Arrange
        val mockRequest = mockk<RagRequest>()
        val mockResponse = mockk<RagResponse>()
        every { mockResponse.request } returns mockRequest

        // Act
        val event1 = InitialRequestRagPipelineEvent(mockRequest, "service-with-dashes")
        val event2 = InitialResponseRagPipelineEvent(mockResponse, "service_with_underscores")

        // Assert
        assertTrue(event1.description.contains("service-with-dashes"))
        assertTrue(event2.description.contains("service_with_underscores"))
    }

    @Test
    fun `events should handle special characters in enhancer names`() {
        // Arrange
        val mockResponse = mockk<RagResponse>()
        val mockRequest = mockk<RagRequest>()
        every { mockResponse.request } returns mockRequest

        // Act
        val event1 = EnhancementStartingRagPipelineEvent(mockResponse, "enhancer-name-123")
        val event2 = EnhancementCompletedRagPipelineEvent(mockResponse, "enhancer_name_456")

        // Assert
        assertTrue(event1.description.contains("enhancer-name-123"))
        assertTrue(event2.description.contains("enhancer_name_456"))
    }
}
