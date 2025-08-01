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
import com.embabel.common.core.StableIdentified
import com.embabel.common.core.types.HasInfoString
import com.embabel.common.core.types.Timestamped
import com.embabel.common.util.indent
import java.time.Instant
import java.util.*

/**
 * Something awaited by an agent process, such as a request for user input.
 * Added to the Blackboard and treated specially.
 */
interface Awaitable<P : Any, R : AwaitableResponse> : StableIdentified, Timestamped, HasInfoString {

    val payload: P

    /**
     * Update process state based on this response
     */
    fun onResponse(
        response: R,
        agentProcess: AgentProcess,
    ): ResponseImpact

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        return "${javaClass.name}(id=$id, payload=$payload, form='$payload')".indent(indent)
    }

}

/**
 * Response to an [Awaitable]
 */
interface AwaitableResponse : StableIdentified, Timestamped {

    /**
     * ID of the Awaitable that this relates to
     */
    val awaitableId: String

}

/**
 * Response of handling an Awaitable
 */
enum class ResponseImpact {
    UPDATED,
    UNCHANGED,
}

/**
 * Convenient support for implementing [Awaitable]
 */
abstract class AbstractAwaitable<P : Any, R : AwaitableResponse>(
    override val payload: P,
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Instant = Instant.now(),
    private val persistent: Boolean = false,
) : Awaitable<P, R> {

    override fun persistent(): Boolean = persistent

}
