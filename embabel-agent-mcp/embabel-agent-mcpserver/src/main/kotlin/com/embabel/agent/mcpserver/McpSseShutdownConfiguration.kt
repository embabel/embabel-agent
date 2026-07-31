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

import org.springframework.ai.mcp.server.webmvc.transport.WebMvcSseServerTransportProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.ContextClosedEvent
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * Closes open SSE connections before Tomcat's graceful shutdown phase begins.
 *
 * [WebMvcSseServerTransportProvider] keeps SSE connections open after MCP calls complete
 * (by design — SSE is a persistent connection). When the application shuts down, Tomcat
 * sees these as active requests and waits up to 30 seconds before aborting.
 *
 * This configuration listens for [ContextClosedEvent], which fires before Tomcat's
 * graceful shutdown lifecycle phase, and calls
 * [WebMvcSseServerTransportProvider.closeGracefully] to drain all active sessions.
 * A [SHUTDOWN_TIMEOUT] prevents the shutdown from hanging if the Reactor pipeline
 * stalls (see MCP Java SDK issue #635).
 *
 * [WebMvcSseServerTransportProvider] is injected with `required = false` because
 * `@ConditionalOnBean` evaluates before Spring AI's autoconfiguration registers the
 * transport bean, causing it to be skipped. Optional injection defers the check to
 * after all beans are created; a null provider means SSE transport is not active and
 * no action is taken.
 *
 * Upstream issues (open as of spring-ai-starter-mcp-server-webmvc 1.1.7 / MCP Java SDK 0.18.2):
 * - [Spring AI #4002](https://github.com/spring-projects/spring-ai/issues/4002)
 * - [MCP Java SDK #635](https://github.com/modelcontextprotocol/java-sdk/issues/635)
 * - [MCP Java SDK #547](https://github.com/modelcontextprotocol/java-sdk/issues/547)
 *
 * Safe to remove when upstream ships a fix: [WebMvcSseServerTransportProvider.closeGracefully]
 * is idempotent — a second call on an already-closed provider iterates an empty session map.
 */
@Configuration
internal class McpSseShutdownConfiguration : ApplicationListener<ContextClosedEvent> {

    @Autowired(required = false)
    private var transportProvider: WebMvcSseServerTransportProvider? = null

    private val logger = LoggerFactory.getLogger(McpSseShutdownConfiguration::class.java)

    override fun onApplicationEvent(event: ContextClosedEvent) {
        val provider = transportProvider ?: return
        logger.info("Closing MCP SSE connections before Tomcat graceful shutdown")
        provider.closeGracefully()
            .timeout(SHUTDOWN_TIMEOUT)
            .onErrorResume { error ->
                logger.warn("MCP SSE graceful shutdown did not complete cleanly: {}", error.message)
                Mono.empty()
            }
            .block()
        logger.info("MCP SSE connections closed")
    }

    companion object {
        private val SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
