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

import io.a2a.spec.StreamingEventKind
import io.a2a.spec.Task
import io.a2a.spec.TaskState
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages task state and history for streaming and resubscription support.
 * Tracks active tasks, completed tasks, and their event history.
 */
@Service
@Profile("a2a")
class TaskStateManager {
    private val logger = LoggerFactory.getLogger(TaskStateManager::class.java)

    private val activeTasks = ConcurrentHashMap<String, TaskInfo>()
    private val completedTasks = ConcurrentHashMap<String, TaskInfo>()

    /**
     * Stores information about a task
     */
    data class TaskInfo(
        val taskId: String,
        val contextId: String,
        val streamId: String,
        val events: MutableList<StreamingEventKind> = mutableListOf(),
        val createdAt: Instant = Instant.now(),
        var completedAt: Instant? = null,
        var currentState: TaskState = TaskState.WORKING
    )

    /**
     * Registers a new task with its stream ID
     */
    fun registerTask(taskId: String, contextId: String, streamId: String) {
        logger.info("Registering task: taskId={}, contextId={}, streamId={}", taskId, contextId, streamId)
        activeTasks[taskId] = TaskInfo(
            taskId = taskId,
            contextId = contextId,
            streamId = streamId
        )
    }

    /**
     * Records an event for a task
     */
    fun recordEvent(taskId: String, event: StreamingEventKind) {
        val taskInfo = activeTasks[taskId] ?: completedTasks[taskId]
        if (taskInfo != null) {
            taskInfo.events.add(event)

            // Update state if event is a Task with status
            if (event is Task) {
                taskInfo.currentState = event.status.state

                // Move to completed if task is in terminal state
                if (isTerminalState(event.status.state)) {
                    taskInfo.completedAt = Instant.now()
                    activeTasks.remove(taskId)
                    completedTasks[taskId] = taskInfo
                    logger.info("Task {} completed with state {}", taskId, event.status.state)
                }
            }
        } else {
            logger.warn("Attempted to record event for unknown task: {}", taskId)
        }
    }

    /**
     * Gets the task info for a given task ID
     */
    fun getTaskInfo(taskId: String): TaskInfo? {
        return activeTasks[taskId] ?: completedTasks[taskId]
    }

    /**
     * Gets the stream ID associated with a task
     */
    fun getStreamId(taskId: String): String? {
        return getTaskInfo(taskId)?.streamId
    }

    /**
     * Gets all events for a task
     */
    fun getTaskEvents(taskId: String): List<StreamingEventKind> {
        return getTaskInfo(taskId)?.events ?: emptyList()
    }

    /**
     * Checks if a task is active
     */
    fun isTaskActive(taskId: String): Boolean {
        return activeTasks.containsKey(taskId)
    }

    /**
     * Checks if a task exists (active or completed)
     */
    fun taskExists(taskId: String): Boolean {
        return activeTasks.containsKey(taskId) || completedTasks.containsKey(taskId)
    }

    /**
     * Updates the stream ID for a task (used during resubscription)
     */
    fun updateStreamId(taskId: String, newStreamId: String) {
        val taskInfo = activeTasks[taskId] ?: completedTasks[taskId]
        if (taskInfo != null) {
            logger.info("Updating stream ID for task {} from {} to {}", taskId, taskInfo.streamId, newStreamId)
            val updatedInfo = taskInfo.copy(streamId = newStreamId)
            if (activeTasks.containsKey(taskId)) {
                activeTasks[taskId] = updatedInfo
            } else {
                completedTasks[taskId] = updatedInfo
            }
        }
    }

    /**
     * Removes old completed tasks (basic cleanup)
     */
    fun cleanupOldTasks(olderThan: Instant) {
        val removed = completedTasks.entries.removeIf { entry ->
            entry.value.completedAt?.isBefore(olderThan) ?: false
        }
        if (removed) {
            logger.info("Cleaned up old completed tasks")
        }
    }

    private fun isTerminalState(state: TaskState): Boolean {
        return state in setOf(
            TaskState.COMPLETED,
            TaskState.FAILED,
            TaskState.CANCELED
        )
    }
}
