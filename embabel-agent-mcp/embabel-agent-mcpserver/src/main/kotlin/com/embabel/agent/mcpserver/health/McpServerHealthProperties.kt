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
package com.embabel.agent.mcpserver.health

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Health / readiness settings for the Embabel MCP server.
 *
 * Bound from `embabel.agent.mcpserver.health.*`.
 */
@ConfigurationProperties(prefix = "embabel.agent.mcpserver.health")
class McpServerHealthProperties {

    /**
     * When false, the Actuator health indicator bean is not registered.
     */
    var enabled: Boolean = true

    /**
     * Minimum registered tool count required for health to report UP once the
     * server is [McpServerInitializationState.READY]. Useful for readiness probes.
     * Default `0` means any tool count is acceptable.
     */
    var minTools: Int = 0
}
