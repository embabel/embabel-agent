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
package com.embabel.agent.api.common.autonomy

import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.hitl.Awaitable
import com.embabel.agent.core.hitl.AwaitableResponse
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProcessExecutionExceptionsTest {

    @Test
    fun `ProcessExecutionFailedException should be created with agentProcess and detail`() {
        // Arrange
        val mockProcess = mockk<AgentProcess>()
        every { mockProcess.id } returns "process-123"

        // Act
        val exception = ProcessExecutionFailedException(
            agentProcess = mockProcess,
            detail = "Execution failed due to timeout"
        )

        // Assert
        assertEquals(mockProcess, exception.agentProcess)
        assertEquals("Execution failed due to timeout", exception.detail)
        assertTrue(exception.message!!.contains("process-123"))
        assertTrue(exception.message!!.contains("failed"))
    }

    @Test
    fun `ProcessExecutionFailedException message should include process id and detail`() {
        // Arrange
        val mockProcess = mockk<AgentProcess>()
        every { mockProcess.id } returns "test-process-456"

        // Act
        val exception = ProcessExecutionFailedException(
            agentProcess = mockProcess,
            detail = "Error: out of memory"
        )

        // Assert
        val message = exception.message!!
        assertTrue(message.contains("test-process-456"))
        assertTrue(message.contains("Error: out of memory"))
    }

    @Test
    fun `ProcessExecutionFailedException should extend ProcessExecutionException`() {
        // Arrange
        val mockProcess = mockk<AgentProcess>()
        every { mockProcess.id } returns "process-1"

        // Act
        val exception = ProcessExecutionFailedException(mockProcess, "detail")

        // Assert
        assertTrue(exception is ProcessExecutionException)
        assertTrue(exception is Exception)
    }

    @Test
    fun `ProcessExecutionTerminatedException should be created with agentProcess and detail`() {
        // Arrange
        val mockProcess = mockk<AgentProcess>()
        every { mockProcess.id } returns "terminated-process"

        // Act
        val exception = ProcessExecutionTerminatedException(
            agentProcess = mockProcess,
            detail = "User terminated the process"
        )

        // Assert
        assertEquals(mockProcess, exception.agentProcess)
        assertEquals("User terminated the process", exception.detail)
        assertTrue(exception.message!!.contains("terminated-process"))
        assertTrue(exception.message!!.contains("terminated"))
    }

    @Test
    fun `ProcessExecutionTerminatedException message should include process id and detail`() {
        // Arrange
        val mockProcess = mockk<AgentProcess>()
        every { mockProcess.id } returns "proc-789"

        // Act
        val exception = ProcessExecutionTerminatedException(
            agentProcess = mockProcess,
            detail = "Manual shutdown"
        )

        // Assert
        val message = exception.message!!
        assertTrue(message.contains("proc-789"))
        assertTrue(message.contains("Manual shutdown"))
    }

    @Test
    fun `ProcessExecutionTerminatedException should extend ProcessExecutionException`() {
        // Arrange
        val mockProcess = mockk<AgentProcess>()
        every { mockProcess.id } returns "process-2"

        // Act
        val exception = ProcessExecutionTerminatedException(mockProcess, "terminated")

        // Assert
        assertTrue(exception is ProcessExecutionException)
    }

    @Test
    fun `ProcessExecutionStuckException should be created with agentProcess and detail`() {
        // Arrange
        val mockProcess = mockk<AgentProcess>()
        every { mockProcess.id } returns "stuck-process"

        // Act
        val exception = ProcessExecutionStuckException(
            agentProcess = mockProcess,
            detail = "Process stuck waiting for resource"
        )

        // Assert
        assertEquals(mockProcess, exception.agentProcess)
        assertEquals("Process stuck waiting for resource", exception.detail)
        assertTrue(exception.message!!.contains("stuck-process"))
        assertTrue(exception.message!!.contains("stuck"))
    }

    @Test
    fun `ProcessExecutionStuckException message should include process id and detail`() {
        // Arrange
        val mockProcess = mockk<AgentProcess>()
        every { mockProcess.id } returns "blocked-123"

        // Act
        val exception = ProcessExecutionStuckException(
            agentProcess = mockProcess,
            detail = "Deadlock detected"
        )

        // Assert
        val message = exception.message!!
        assertTrue(message.contains("blocked-123"))
        assertTrue(message.contains("Deadlock detected"))
    }

    @Test
    fun `ProcessExecutionStuckException should extend ProcessExecutionException`() {
        // Arrange
        val mockProcess = mockk<AgentProcess>()
        every { mockProcess.id } returns "process-3"

        // Act
        val exception = ProcessExecutionStuckException(mockProcess, "stuck")

        // Assert
        assertTrue(exception is ProcessExecutionException)
    }

    @Test
    fun `ProcessWaitingException should be created with agentProcess and awaitable`() {
        // Arrange
        val mockProcess = mockk<AgentProcess>()
        val mockAwaitable = mockk<Awaitable<Any, AwaitableResponse>>()
        every { mockProcess.id } returns "waiting-process"
        every { mockAwaitable.infoString(any(), any()) } returns "awaiting user input"

        // Act
        val exception = ProcessWaitingException(
            agentProcess = mockProcess,
            awaitable = mockAwaitable
        )

        // Assert
        assertEquals(mockProcess, exception.agentProcess)
        assertEquals(mockAwaitable, exception.awaitable)
        assertTrue(exception.message!!.contains("waiting-process"))
        assertTrue(exception.message!!.contains("waiting"))
    }

    @Test
    fun `ProcessWaitingException message should include process id and awaitable info`() {
        // Arrange
        val mockProcess = mockk<AgentProcess>()
        val mockAwaitable = mockk<Awaitable<Any, AwaitableResponse>>()
        every { mockProcess.id } returns "wait-456"
        every { mockAwaitable.infoString(any(), any()) } returns "user confirmation"

        // Act
        val exception = ProcessWaitingException(
            agentProcess = mockProcess,
            awaitable = mockAwaitable
        )

        // Assert
        val message = exception.message!!
        assertTrue(message.contains("wait-456"))
        assertTrue(message.contains("user confirmation"))
    }

    @Test
    fun `ProcessWaitingException should extend ProcessExecutionException`() {
        // Arrange
        val mockProcess = mockk<AgentProcess>()
        val mockAwaitable = mockk<Awaitable<Any, AwaitableResponse>>()
        every { mockProcess.id } returns "process-4"
        every { mockAwaitable.infoString(any(), any()) } returns "waiting"

        // Act
        val exception = ProcessWaitingException(mockProcess, mockAwaitable)

        // Assert
        assertTrue(exception is ProcessExecutionException)
    }

    @Test
    fun `ProcessWaitingException should handle complex awaitable info strings`() {
        // Arrange
        val mockProcess = mockk<AgentProcess>()
        val mockAwaitable = mockk<Awaitable<Any, AwaitableResponse>>()
        every { mockProcess.id } returns "complex-process"
        every { mockAwaitable.infoString(any(), any()) } returns "waiting for: [approval, signature, payment]"

        // Act
        val exception = ProcessWaitingException(mockProcess, mockAwaitable)

        // Assert
        assertTrue(exception.message!!.contains("waiting for: [approval, signature, payment]"))
    }

    @Test
    fun `all exceptions should be throwable`() {
        // Arrange
        val mockProcess = mockk<AgentProcess>()
        val mockAwaitable = mockk<Awaitable<Any, AwaitableResponse>>()
        every { mockProcess.id } returns "throw-test"
        every { mockAwaitable.infoString(any(), any()) } returns "info"

        // Act & Assert
        assertThrows(ProcessExecutionFailedException::class.java) {
            throw ProcessExecutionFailedException(mockProcess, "failed")
        }

        assertThrows(ProcessExecutionTerminatedException::class.java) {
            throw ProcessExecutionTerminatedException(mockProcess, "terminated")
        }

        assertThrows(ProcessExecutionStuckException::class.java) {
            throw ProcessExecutionStuckException(mockProcess, "stuck")
        }

        assertThrows(ProcessWaitingException::class.java) {
            throw ProcessWaitingException(mockProcess, mockAwaitable)
        }
    }

    @Test
    fun `exceptions should be catchable as ProcessExecutionException`() {
        // Arrange
        val mockProcess = mockk<AgentProcess>()
        val mockAwaitable = mockk<Awaitable<Any, AwaitableResponse>>()
        every { mockProcess.id } returns "catch-test"
        every { mockAwaitable.infoString(any(), any()) } returns "info"

        // Act & Assert - ProcessExecutionFailedException
        try {
            throw ProcessExecutionFailedException(mockProcess, "test")
        } catch (e: ProcessExecutionException) {
            assertTrue(e is ProcessExecutionFailedException)
        }

        // Act & Assert - ProcessExecutionTerminatedException
        try {
            throw ProcessExecutionTerminatedException(mockProcess, "test")
        } catch (e: ProcessExecutionException) {
            assertTrue(e is ProcessExecutionTerminatedException)
        }

        // Act & Assert - ProcessExecutionStuckException
        try {
            throw ProcessExecutionStuckException(mockProcess, "test")
        } catch (e: ProcessExecutionException) {
            assertTrue(e is ProcessExecutionStuckException)
        }

        // Act & Assert - ProcessWaitingException
        try {
            throw ProcessWaitingException(mockProcess, mockAwaitable)
        } catch (e: ProcessExecutionException) {
            assertTrue(e is ProcessWaitingException)
        }
    }

    @Test
    fun `ProcessExecutionFailedException should handle empty detail`() {
        // Arrange
        val mockProcess = mockk<AgentProcess>()
        every { mockProcess.id } returns "empty-detail"

        // Act
        val exception = ProcessExecutionFailedException(mockProcess, "")

        // Assert
        assertEquals("", exception.detail)
        assertNotNull(exception.message)
    }

    @Test
    fun `ProcessExecutionTerminatedException should handle empty detail`() {
        // Arrange
        val mockProcess = mockk<AgentProcess>()
        every { mockProcess.id } returns "empty-detail"

        // Act
        val exception = ProcessExecutionTerminatedException(mockProcess, "")

        // Assert
        assertEquals("", exception.detail)
        assertNotNull(exception.message)
    }

    @Test
    fun `ProcessExecutionStuckException should handle empty detail`() {
        // Arrange
        val mockProcess = mockk<AgentProcess>()
        every { mockProcess.id } returns "empty-detail"

        // Act
        val exception = ProcessExecutionStuckException(mockProcess, "")

        // Assert
        assertEquals("", exception.detail)
        assertNotNull(exception.message)
    }
}
