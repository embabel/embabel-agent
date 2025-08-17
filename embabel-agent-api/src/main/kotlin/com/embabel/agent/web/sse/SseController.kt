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
package com.embabel.agent.web.sse

import com.embabel.agent.config.AgentPlatformProperties
import com.embabel.agent.event.AgentProcessEvent
import com.embabel.agent.event.AgenticEventListener
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.util.*
import java.util.concurrent.ConcurrentHashMap

// MIGRATED: @ConfigurationProperties(prefix = "embabel.sse") → AgentPlatformProperties.sse
// Properties now sourced from embabel.agent.platform.sse.* in agent-platform.properties
@Component
class SseProperties(platformProperties: AgentPlatformProperties) {
    val maxBufferSize: Int = platformProperties.sse.maxBufferSize
    val maxProcessBuffers: Int = platformProperties.sse.maxProcessBuffers
}

/**
 * Spring Controller for Server-Sent Events (SSE) streaming of AgentProcessEvents.
 * This controller by being registered as a bean via the [RestController] annotation
 * will automatically listen for [AgentProcessEvent]s because it implements
 * [AgenticEventListener].
 * Each new listener will receive all events for that process to date.
 */
@RestController
class SSEController(
    private val sseProperties: SseProperties,
) : AgenticEventListener {

    private val logger = LoggerFactory.getLogger(SSEController::class.java)

    init {
        logger.info("SSEController initialized, ready to stream AgentProcessEvents...")
    }

    // Map from processId to a list of SseEmitters
    private val processEmitters = ConcurrentHashMap<String, MutableList<SseEmitter>>()

    // Buffer recent events per process
    private val eventBuffer = ConcurrentHashMap<String, MutableList<AgentProcessEvent>>()

    override fun onProcessEvent(event: AgentProcessEvent) {
        val processId = event.processId

        synchronized(eventBuffer) {
            // Remove and re-add to move to end (most recently used)
            val buffer = eventBuffer.remove(processId) ?: Collections.synchronizedList(mutableListOf())

            // Add event to buffer
            synchronized(buffer) {
                buffer.add(event)
                if (buffer.size > sseProperties.maxBufferSize) {
                    buffer.removeAt(0) // Remove oldest event
                }
            }

            // Put buffer back (now at end of LinkedHashMap)
            eventBuffer[processId] = buffer

            // Evict oldest process buffer if we exceed limit
            if (eventBuffer.size > sseProperties.maxProcessBuffers) {
                val oldestProcessId = eventBuffer.keys.first()
                eventBuffer.remove(oldestProcessId)
                logger.debug("Evicted oldest process buffer: {}", oldestProcessId)
            }
        }

        val emitters = processEmitters[processId]
        emitters?.removeIf { emitter ->
            try {
                logger.debug("Sending SSE event for process {}: {}", processId, event)
                emitter.send(
                    SseEmitter.event()
                        .name(SSE_EVENT_NAME)
                        .data(event)
                )
                false // Keep this emitter
            } catch (_: IOException) {
                logger.debug("Disconnecting emitter for process {}", processId)
                true // Remove broken emitter
            } catch (t: Throwable) {
                logger.warn("Error sending event to emitter for process $processId", t)
                true
            }
        }
    }

    @GetMapping(value = ["/events/process/{processId}"], produces = [TEXT_EVENT_STREAM_VALUE])
    fun streamEventsForId(@PathVariable processId: String): SseEmitter {
        val emitter = SseEmitter(Long.MAX_VALUE)

        logger.debug("SSE streaming active for process {}", processId)

        // Add emitter to the map for this process
        processEmitters.computeIfAbsent(processId) {
            Collections.synchronizedList(mutableListOf())
        }.add(emitter)

        // Clean up when connection closes
        emitter.onCompletion {
            removeEmitter(processId, emitter)
        }
        emitter.onTimeout {
            removeEmitter(processId, emitter)
        }
        emitter.onError {
            removeEmitter(processId, emitter)
        }

        try {
            // Send any earlier events from the buffer
            eventBuffer[processId]?.let { buffer ->
                for (event in buffer) {
                    logger.debug("Catchup: Sending buffered event for process {}: {}", processId, event)
                    emitter.send(SseEmitter.event().name(SSE_EVENT_NAME).data(event))
                }
            }

            emitter.send(
                SseEmitter.event()
                    .name("connected")
                    .data(mapOf("message" to "Connected to stream for process ID: $processId"))
            )
        } catch (e: Exception) {
            emitter.completeWithError(e)
        }

        return emitter
    }

    private fun removeEmitter(processId: String, emitter: SseEmitter) {
        processEmitters[processId]?.remove(emitter)
        if (processEmitters[processId]?.isEmpty() == true) {
            processEmitters.remove(processId)
        }
    }

    companion object {

        const val SSE_EVENT_NAME = "agent-process-event"
    }

}
