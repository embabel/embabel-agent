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

import com.embabel.agent.a2a.config.A2AConfigurationProperties
import io.a2a.spec.StreamingEventKind
import io.a2a.spec.Task
import io.a2a.spec.TaskState
import io.a2a.spec.TaskStatus
import io.a2a.spec.TaskStatusUpdateEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.annotation.concurrent.Immutable
import javax.annotation.concurrent.ThreadSafe

/**
 * Manages task state and history for streaming and resubscription support.
 * Tracks active tasks, completed tasks, and their event history.
 * Completed tasks are evicted once they have been finished for longer than [taskRetention].
 */
@Service
@ThreadSafe
class TaskStateManager @JvmOverloads constructor(
    val taskRetention: Duration = A2AConfigurationProperties.DEFAULT_TASK_RETENTION,
    private val clock: Clock = Clock.systemUTC(),
) {

    @Autowired
    constructor(properties: A2AConfigurationProperties) : this(properties.server.taskRetention)

    private val logger = LoggerFactory.getLogger(TaskStateManager::class.java)

    private val tasks = ConcurrentHashMap<String, TaskInfo>()

    /**
     * Stores information about a task. State changes replace the whole value atomically,
     * while the event list is shared by all values of a task.
     */
    @Immutable
    data class TaskInfo(
        val taskId: String,
        val contextId: String,
        val streamId: String? = null,
        val events: CopyOnWriteArrayList<StreamingEventKind> = CopyOnWriteArrayList(),
        val createdAt: Instant = Instant.now(),
        val completedAt: Instant? = null,
        val currentState: TaskState = TaskState.WORKING
    )

    /**
     * Registers a new task, with the stream ID when it is streamed
     */
    @JvmOverloads
    fun registerTask(taskId: String, contextId: String, streamId: String? = null) {
        logger.info("Registering task: taskId={}, contextId={}, streamId={}", taskId, contextId, streamId)
        val now = clock.instant()
        cleanupOldTasks(now.minus(taskRetention))
        tasks[taskId] = TaskInfo(
            taskId = taskId,
            contextId = contextId,
            streamId = streamId,
            createdAt = now,
        )
    }

    /**
     * Records an event for a task
     */
    fun recordEvent(taskId: String, event: StreamingEventKind) {
        val taskInfo = tasks.computeIfPresent(taskId) { _, taskInfo ->
            taskInfo.events.add(event)
            val taskState = statusOf(event)?.state
            when {
                taskState == null -> taskInfo
                taskState.isFinal -> {
                    logger.info("Task {} completed with state {}", taskId, taskState)
                    taskInfo.copy(currentState = taskState, completedAt = clock.instant())
                }

                else -> taskInfo.copy(currentState = taskState)
            }
        }
        if (taskInfo == null) {
            logger.warn("Attempted to record event for unknown task: {}", taskId)
        }
    }

    /**
     * Gets the task info for a given task ID
     */
    fun getTaskInfo(taskId: String): TaskInfo? {
        return tasks[taskId]
    }

    /**
     * Gets the task as of its latest recorded event
     */
    fun getTask(taskId: String): Task? {
        val taskInfo = getTaskInfo(taskId) ?: return null
        val latestTask = taskInfo.events.filterIsInstance<Task>().lastOrNull()
        val latestStatus = taskInfo.events.mapNotNull { statusOf(it) }.lastOrNull()
            ?: TaskStatus(taskInfo.currentState)
        return Task(
            taskId,
            taskInfo.contextId,
            latestStatus,
            latestTask?.artifacts ?: emptyList(),
            latestTask?.history ?: emptyList(),
            latestTask?.metadata,
        )
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
        return getTaskInfo(taskId)?.currentState?.isFinal == false
    }

    /**
     * Checks if a task exists (active or completed)
     */
    fun taskExists(taskId: String): Boolean {
        return tasks.containsKey(taskId)
    }

    /**
     * Updates the stream ID for a task (used during resubscription)
     */
    fun updateStreamId(taskId: String, newStreamId: String) {
        tasks.computeIfPresent(taskId) { _, taskInfo ->
            logger.info("Updating stream ID for task {} from {} to {}", taskId, taskInfo.streamId, newStreamId)
            taskInfo.copy(streamId = newStreamId)
        }
    }

    /**
     * Removes old completed tasks (basic cleanup)
     */
    fun cleanupOldTasks(olderThan: Instant) {
        val removed = tasks.values.removeIf { taskInfo ->
            taskInfo.completedAt?.isBefore(olderThan) ?: false
        }
        if (removed) {
            logger.info("Cleaned up old completed tasks")
        }
    }

    private fun statusOf(event: StreamingEventKind): TaskStatus? = when (event) {
        is Task -> event.status
        is TaskStatusUpdateEvent -> event.status
        else -> null
    }
}
