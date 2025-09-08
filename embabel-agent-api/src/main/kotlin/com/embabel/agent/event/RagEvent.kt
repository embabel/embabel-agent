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
package com.embabel.agent.event

import com.embabel.agent.rag.RagRequest
import com.embabel.agent.rag.RagResponse
import com.embabel.common.core.types.Timestamped
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Events relating to RAG.  RagServices are responsible
 * for publishing these events.
 * Individual RagServices may publish additional subclasses
 */
interface RagEvent : Timestamped {

    val request: RagRequest

}

class RagRequestReceivedEvent(
    override val request: RagRequest,
    override val timestamp: Instant = Instant.now(),
) : RagEvent

class RagResponseEvent(
    val ragResponse: RagResponse,
    override val timestamp: Instant = Instant.now(),
) : RagEvent {

    override val request: RagRequest
        get() = ragResponse.request
}

fun interface RagEventListener {

    fun onRagEvent(event: RagEvent)

    operator fun plus(other: RagEventListener): RagEventListener {
        if (this === NOOP) return other
        if (other === NOOP) return this
        return MulticastRagEventListener(listOf(this, other))
    }

    companion object {
        val NOOP = RagEventListener {
        }
    }
}

private class MulticastRagEventListener(
    private val listeners: List<RagEventListener>,
) : RagEventListener {

    override fun onRagEvent(event: RagEvent) {
        listeners.forEach {
            try {
                it.onRagEvent(event)
            } catch (ex: Exception) {
                // Log and continue
                LoggerFactory.getLogger(RagEventListener::class.java)
                    .error("Error in RAG event listener", ex)
            }
        }
    }
}
