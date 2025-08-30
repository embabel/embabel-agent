/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.a2a.server

import com.embabel.common.core.types.HasInfoString
import io.a2a.spec.AgentCard

/**
 * Exposes an A2A AgentCard and handles JSON-RPC requests routed to its path
 */
interface AgentCardHandler : A2ARequestHandler, HasInfoString {

    /**
     * Relative path below root
     */
    val path: String

    /**
     * Returns the agent card for the A2A server.
     * We need to provide the scheme, host, and port so that the agent card
     * can compute the correct URL for its POST endpoint.
     */
    fun agentCard(scheme: String, host: String, port: Int): AgentCard

}
