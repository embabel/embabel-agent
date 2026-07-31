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
package com.embabel.agent.mcpserver

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcSseServerTransportProvider
import org.springframework.context.event.ContextClosedEvent
import reactor.core.publisher.Mono
import java.time.Duration

class McpSseShutdownConfigurationTest {

    private val transportProvider = mockk<WebMvcSseServerTransportProvider>()
    private val config = McpSseShutdownConfiguration().also {
        val field = McpSseShutdownConfiguration::class.java.getDeclaredField("transportProvider")
        field.isAccessible = true
        field.set(it, transportProvider)
    }
    private val event = mockk<ContextClosedEvent>(relaxed = true)

    @Nested
    inner class `on ContextClosedEvent` {

        @Test
        fun `calls closeGracefully on transport provider`() {
            every { transportProvider.closeGracefully() } returns Mono.empty()
            config.onApplicationEvent(event)
            verify(exactly = 1) { transportProvider.closeGracefully() }
        }

        @Test
        fun `completes without throwing when closeGracefully times out`() {
            every { transportProvider.closeGracefully() } returns Mono.never()
            config.onApplicationEvent(event)
            // no exception propagated — shutdown proceeds regardless
        }

        @Test
        fun `completes without throwing when closeGracefully errors`() {
            every { transportProvider.closeGracefully() } returns Mono.error(RuntimeException("transport error"))
            config.onApplicationEvent(event)
        }
    }
}
