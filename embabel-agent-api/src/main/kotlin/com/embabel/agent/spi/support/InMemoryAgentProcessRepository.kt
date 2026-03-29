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
package com.embabel.agent.spi.support

import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessRepository
import com.embabel.agent.spi.config.spring.ProcessRepositoryProperties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * In-memory implementation of [AgentProcessRepository] with configurable window size
 * to prevent memory overflow by evicting the oldest entries when the limit is reached.
 */
class InMemoryAgentProcessRepository(
    private val properties: ProcessRepositoryProperties = ProcessRepositoryProperties(),
) : AgentProcessRepository {

    private val map: ConcurrentHashMap<String, AgentProcess> = ConcurrentHashMap()
    private val accessOrder: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
    private val lock = ReentrantReadWriteLock()

    override fun findById(id: String): AgentProcess? = lock.read {
        map[id]
    }

    override fun findByParentId(parentId: String): List<AgentProcess> = lock.read {
        findByParentIdInternal(parentId)
    }

    private fun findByParentIdInternal(parentId: String): List<AgentProcess> =
        map.values.filter { it.parentId == parentId }

    /**
     * Check if the entire process hierarchy (process + all descendants) is finished.
     * Returns true only if the process AND all its children recursively are finished.
     */
    private fun isHierarchyFinished(processId: String): Boolean {
        val process = map[processId] ?: return true
        if (!process.finished) return false
        return findByParentIdInternal(processId).all { isHierarchyFinished(it.id) }
    }

    /**
     * Evict an entire process hierarchy (process + all descendants).
     * Must only be called when [isHierarchyFinished] returns true.
     */
    private fun evictHierarchy(processId: String) {
        findByParentIdInternal(processId).forEach { child ->
            evictHierarchy(child.id)
        }
        map.remove(processId)
        accessOrder.remove(processId)
    }

    override fun save(agentProcess: AgentProcess): AgentProcess = lock.write {
        val processId = agentProcess.id

        // If this process already exists, remove it from access order to re-add at end
        if (map.containsKey(processId)) {
            accessOrder.remove(processId)
        }

        map[processId] = agentProcess

        // Only track root processes for eviction.
        // Child processes are evicted together with their parent hierarchy.
        if (agentProcess.isRootProcess) {
            accessOrder.offer(processId)

            // Eviction rules:
            // 1. Only evict entire hierarchies (root + all descendants), never partial
            // 2. Only evict if the entire hierarchy is finished (no running processes)
            // This ensures findByParentId always finds active children for kill propagation.
            while (accessOrder.size > properties.windowSize) {
                val oldestRootId = accessOrder.peek() ?: break
                if (isHierarchyFinished(oldestRootId)) {
                    accessOrder.poll()
                    evictHierarchy(oldestRootId)
                } else {
                    // Oldest hierarchy still running - stop eviction attempts
                    // to preserve FIFO order and prevent skipping
                    break
                }
            }
        }

        agentProcess
    }

    override fun update(agentProcess: AgentProcess) {
        // Nothing to do here as the reference is already updated in memory
    }

    override fun delete(agentProcess: AgentProcess) {
        lock.write {
            val processId = agentProcess.id
            map.remove(processId)
            accessOrder.remove(processId)
        }
    }

    /**
     * Get current size of the repository for testing purposes.
     */
    fun size(): Int = lock.read { map.size }

    /**
     * Clear all entries from the repository for testing purposes.
     */
    fun clear() = lock.write {
        map.clear()
        accessOrder.clear()
    }
}
