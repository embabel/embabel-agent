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
package com.embabel.agent.core.hitl

import com.embabel.agent.core.AgentProcess
import com.embabel.common.util.indent
import com.embabel.common.util.loggerFor
import java.time.Instant
import java.util.*

/**
 * Request a value of type [T] from the user.
 *
 * The UX layer will typically generate a form from the type's schema
 * and present it to the user. When the user provides the value, it
 * is added to the blackboard and execution can continue.
 *
 * @param T The type of value to request
 * @param type The class of the requested type (for reflection/schema generation)
 * @param message Optional message to display to the user explaining what's needed
 * @param hint Optional pre-populated value to show as a starting point
 * @param persistent Whether this request should be persisted
 */
class TypeRequest<T : Any> @JvmOverloads constructor(
    val type: Class<T>,
    val message: String? = null,
    val hint: T? = null,
    persistent: Boolean = false,
) : AbstractAwaitable<Class<T>, TypeResponse<T>>(
    payload = type,
    persistent = persistent,
) {

    private val logger = loggerFor<TypeRequest<T>>()

    override fun onResponse(
        response: TypeResponse<T>,
        agentProcess: AgentProcess,
    ): ResponseImpact {
        logger.info("Received type response: {}", response.value)
        agentProcess += response.value
        return ResponseImpact.UPDATED
    }

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        val msgPart = message?.let { ", message='$it'" } ?: ""
        val hintPart = hint?.let { ", hint=$it" } ?: ""
        return "TypeRequest(id=$id, type=${type.simpleName}$msgPart$hintPart)".indent(indent)
    }

    override fun toString(): String = infoString(verbose = false)
}

/**
 * Response providing a value of the requested type.
 *
 * @param T The type of value provided
 * @param value The value provided by the user
 * @param awaitableId The ID of the TypeRequest this responds to
 */
data class TypeResponse<T : Any>(
    val value: T,
    override val awaitableId: String,
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Instant = Instant.now(),
    private val persistent: Boolean = false,
) : AwaitableResponse {

    override fun persistent() = persistent
}

/**
 * Kotlin-friendly factory for creating TypeRequest with reified type.
 */
inline fun <reified T : Any> typeRequest(
    message: String? = null,
    hint: T? = null,
    persistent: Boolean = false,
): TypeRequest<T> = TypeRequest(
    type = T::class.java,
    message = message,
    hint = hint,
    persistent = persistent,
)
