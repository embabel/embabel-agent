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
package com.embabel.agent.core.hitl

import com.embabel.agent.core.AgentProcess
import com.embabel.common.util.indent
import com.embabel.common.util.loggerFor
import java.time.Instant
import java.util.*

/**
 * Request confirmation from the user before promoting
 * an object to the blackboard. Rejection will hold back a flow.
 */
class ConfirmationRequest<P : Any>(
    payload: P,
    val message: String,
    persistent: Boolean = false,
) : AbstractAwaitable<P, ConfirmationResponse>(
    payload = payload,
    persistent = persistent,
) {

    override fun onResponse(
        response: ConfirmationResponse,
        agentProcess: AgentProcess,
    ): ResponseImpact {

        return if (response.accepted) {
            loggerFor<ConfirmationRequest<*>>().info(
                "Accepted confirmation request. Promoting payload to blackboard: {}",
                payload,
            )
            agentProcess += payload
            ResponseImpact.UPDATED
        } else {
            loggerFor<ConfirmationRequest<*>>().info("Rejected confirmation request: {}", payload)
            ResponseImpact.UNCHANGED
        }
    }

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        return "ConfirmationRequest(id=$id, payload=$payload, message='$message')".indent(indent)
    }

    override fun toString(): String {
        return "ConfirmationRequest(id=$id, payloadType:${payload::class.qualifiedName}, message='$message')"
    }
}

data class ConfirmationResponse(
    override val id: String = UUID.randomUUID().toString(),
    override val awaitableId: String,
    val accepted: Boolean,
    private val persistent: Boolean = false,
    override val timestamp: Instant = Instant.now(),
) : AwaitableResponse {

    override fun persistent() = persistent
}
