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
package com.embabel.agent.a2a.server.support

import com.embabel.common.util.EmbabelObjectMapperHolder
import io.a2a.spec.SendStreamingMessageResponse
import io.a2a.spec.StreamingEventKind
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import javax.annotation.concurrent.ThreadSafe
import kotlin.concurrent.withLock

/**
 * Handles streaming functionality for A2A messages.
 * Task events are recorded whether or not a client is connected, and delivered to every stream subscribed to the task.
 */
@Service
@ThreadSafe
class A2AStreamingHandler(
    private val embabelObjectMapperHolder: EmbabelObjectMapperHolder,
    private val taskStateManager: TaskStateManager
) {
    private val logger = LoggerFactory.getLogger(A2AStreamingHandler::class.java)
    private val activeStreams = ConcurrentHashMap<String, ActiveStream>()
    private val taskSubscribers = ConcurrentHashMap<String, TaskSubscribers>()

    /**
     * An open stream, with the JSON-RPC id of the request it answers
     */
    private data class ActiveStream(
        val emitter: SseEmitter,
        val requestId: Any?,
    )

    /**
     * Publishing an event and replaying the task to a new subscriber both hold [lock],
     * so a subscriber receives every event once, in order, and the final event before its stream closes.
     */
    private class TaskSubscribers {
        val lock = ReentrantLock()
        val streamIds = mutableSetOf<String>()
    }

    /**
     * Creates a new SSE stream for the given stream ID
     * @param streamId Server-side key of the stream. Must be unique, so it must not be the client's request id
     * @param taskId Optional task ID to associate with this stream
     * @param requestId JSON-RPC id of the request, sent back unchanged in every event
     */
    fun createStream(
        streamId: String,
        taskId: String? = null,
        contextId: String? = null,
        requestId: Any? = streamId,
    ): SseEmitter {
        logger.info("Creating SSE stream for streamId: {}, taskId: {}", streamId, taskId)

        val emitter = SseEmitter(Long.MAX_VALUE)
        activeStreams[streamId] = ActiveStream(emitter, requestId)

        // Register task if taskId is provided
        if (taskId != null && contextId != null) {
            taskStateManager.registerTask(taskId, contextId, streamId)
            subscribersOf(taskId).let { subscribers ->
                subscribers.lock.withLock { subscribers.streamIds.add(streamId) }
            }
        }

        emitter.onCompletion {
            logger.info("Stream completed for streamId: {}", streamId)
            activeStreams.remove(streamId)
        }

        emitter.onTimeout {
            logger.info("Stream timed out for streamId: {}", streamId)
            activeStreams.remove(streamId)
        }

        return emitter
    }

    /**
     * Resubscribes to an existing task by creating a new stream and replaying events
     */
    fun resubscribeToTask(taskId: String, newStreamId: String, requestId: Any? = newStreamId): SseEmitter {
        logger.info("Resubscribing to task: taskId={}, newStreamId={}", taskId, newStreamId)

        // Check if task exists
        if (!taskStateManager.taskExists(taskId)) {
            throw IllegalArgumentException("Task not found: $taskId")
        }

        // Attach the new stream without registering the task again, which would reset its state
        val emitter = createStream(newStreamId, requestId = requestId)
        taskStateManager.updateStreamId(taskId, newStreamId)

        // Replay without recording again; the emitter buffers events until the response is ready
        val subscribers = subscribersOf(taskId)
        subscribers.lock.withLock {
            taskStateManager.getTaskEvents(taskId).forEach { event ->
                sendStreamEvent(newStreamId, event)
            }
            if (taskStateManager.isTaskActive(taskId)) {
                subscribers.streamIds.add(newStreamId)
            } else {
                closeStream(newStreamId)
                taskSubscribers.remove(taskId, subscribers)
            }
        }

        return emitter
    }

    /**
     * Records an event for the task and sends it to the streams subscribed to the task.
     * The streams are closed once the task is finished.
     */
    fun publishTaskEvent(taskId: String, event: StreamingEventKind) {
        val subscribers = subscribersOf(taskId)
        subscribers.lock.withLock {
            taskStateManager.recordEvent(taskId, event)
            subscribers.streamIds.retainAll(activeStreams.keys)
            subscribers.streamIds.forEach { streamId ->
                sendStreamEvent(streamId, event)
            }
            if (!taskStateManager.isTaskActive(taskId)) {
                subscribers.streamIds.forEach(::closeStream)
                subscribers.streamIds.clear()
                taskSubscribers.remove(taskId, subscribers)
            }
        }
    }

    private fun subscribersOf(taskId: String): TaskSubscribers =
        taskSubscribers.computeIfAbsent(taskId) { TaskSubscribers() }

    /**
     * Sends a streaming event to the specified stream without recording it
     */
    fun sendStreamEvent(streamId: String, event: StreamingEventKind) {
        val stream = activeStreams[streamId] ?: run {
            logger.warn("No active stream found for streamId: {}", streamId)
            return
        }
        val emitter = stream.emitter

        try {
            // All events must be wrapped in SendStreamingMessageResponse per A2A protocol
            val response = SendStreamingMessageResponse(
                "2.0",
                stream.requestId,
                event,
                null
            )

            val eventData = SseEmitter.event()
                .data(embabelObjectMapperHolder.get().writeValueAsString(response), MediaType.APPLICATION_JSON)
            emitter.send(eventData)
        } catch (e: Exception) {
            logger.error("Error sending stream event", e)
            emitter.completeWithError(e)
        }
    }

    /**
     * Closes the specified stream
     */
    fun closeStream(streamId: String) {
        activeStreams.remove(streamId)?.emitter?.complete()
    }
}
