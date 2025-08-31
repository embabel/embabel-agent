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
package com.embabel.agent.a2a.server.support

import com.fasterxml.jackson.databind.ObjectMapper
import io.a2a.spec.Message
import io.a2a.spec.SendStreamingMessageResponse
import io.a2a.spec.StreamingEventKind
import io.a2a.spec.Task
import io.a2a.spec.TaskArtifactUpdateEvent
import io.a2a.spec.TaskStatusUpdateEvent
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Handles streaming functionality for A2A messages
 */
@Service
@Profile("a2a")
class A2AStreamingHandler(
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(A2AStreamingHandler::class.java)
    private val activeStreams = ConcurrentHashMap<String, SseEmitter>()
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)

    /**
     * Creates a new SSE stream for the given stream ID
     */
    fun createStream(streamId: String): SseEmitter {
        logger.info("Creating SSE stream for streamId: {}", streamId)

        val emitter = SseEmitter(Long.MAX_VALUE)
        activeStreams[streamId] = emitter

        emitter.onCompletion {
            logger.info("Stream completed for streamId: {}", streamId)
            activeStreams.remove(streamId)
        }

        emitter.onTimeout {
            logger.info("Stream timed out for streamId: {}", streamId)
            activeStreams.remove(streamId)
        }

        emitter.onError { throwable ->
            logger.error("Stream error for streamId: {}", streamId, throwable)
            activeStreams.remove(streamId)
        }

        return emitter
    }

    /**
     * Sends a streaming event to the specified stream
     */
    fun sendStreamEvent(streamId: String, event: StreamingEventKind) {
        val emitter = activeStreams[streamId] ?: run {
            logger.warn("No active stream found for streamId: {}", streamId)
            return
        }

        try {
            val eventData = when (event) {
                is Message, is Task, is TaskArtifactUpdateEvent, is TaskStatusUpdateEvent -> {
                    createEventData(streamId, event)
                }
            }
            emitter.send(eventData)
        } catch (e: Exception) {
            logger.error("Error sending stream event to streamId: {}", streamId, e)
            val emitter = activeStreams.remove(streamId)
            emitter?.completeWithError(e)
        }
    }

    /**
     * Closes the specified stream
     */
    fun closeStream(streamId: String) {
        val emitter = activeStreams.remove(streamId)
        emitter?.complete()
    }

    private fun createEventData(streamId: String, event: StreamingEventKind): SseEmitter.SseEventBuilder {
        val eventName = when (event) {
            is Message -> "message"
            is Task -> "task"
            is TaskStatusUpdateEvent, is TaskArtifactUpdateEvent -> "task-update"
        }

        val response = SendStreamingMessageResponse(
            "2.0",
            streamId,
            event,
            null
        )

        return SseEmitter.event()
            .name(eventName)
            .data(objectMapper.writeValueAsString(response), MediaType.APPLICATION_JSON)
    }

    /**
     * Shuts down the streaming handler
     */
    fun shutdown() {
        logger.info("Shutting down, closing {} active streams", activeStreams.size)

        activeStreams.values.forEach { emitter ->
            try {
                emitter.complete()
            } catch (e: Exception) {
                logger.warn("Error completing emitter during shutdown", e)
            }
        }
        activeStreams.clear()

        scheduler.shutdown()
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("Scheduler did not terminate gracefully, forcing shutdown")
                scheduler.shutdownNow()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            scheduler.shutdownNow()
        }
    }
}
