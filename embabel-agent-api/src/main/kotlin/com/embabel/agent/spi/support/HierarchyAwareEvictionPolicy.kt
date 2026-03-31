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
import java.util.*

/**
 * Eviction policy for agent process hierarchies in memory-bounded storage.
 *
 * NOTE: This policy is applicable only to in-memory stores where eviction is necessary
 * to prevent memory overflow. Disk or cloud-based storage implementations typically
 * do not require eviction as storage is scalable; cleanup is instead handled via
 * TTL policies, scheduled jobs, or explicit deletion based on retention requirements.
 *
 * Eviction rules:
 * 1. Only evict entire hierarchies (root + all descendants), never partial
 * 2. Only evict if the entire hierarchy is finished (no running processes)
 *
 * This ensures findByParentId always finds active children for kill propagation.
 *
 * @param windowSize Maximum number of root processes to retain
 */
internal class HierarchyAwareEvictionPolicy(private val windowSize: Int) {

    /**
     * Evict oldest finished hierarchies if accessOrder exceeds windowSize.
     *
     * @param accessOrder Queue of root process IDs in access order (oldest first)
     * @param map The process map to evict from
     */
    fun evictIfNeeded(accessOrder: Queue<String>, map: MutableMap<String, AgentProcess>) {
        while (accessOrder.size > windowSize) {
            val oldestRootId = accessOrder.peek() ?: break
            if (isHierarchyFinished(oldestRootId, map)) {
                accessOrder.poll()
                evictHierarchy(oldestRootId, map, accessOrder)
            } else {
                // Oldest hierarchy still running - stop eviction attempts
                // to preserve FIFO order and prevent skipping
                break
            }
        }
    }

    /**
     * Check if the entire process hierarchy (process + all descendants) is finished.
     * Returns true only if the process AND all its children recursively are finished.
     */
    private fun isHierarchyFinished(processId: String, map: Map<String, AgentProcess>): Boolean {
        val process = map[processId] ?: return true
        if (!process.finished) return false
        return findChildrenOf(processId, map).all { isHierarchyFinished(it.id, map) }
    }

    /**
     * Evict an entire process hierarchy (process + all descendants).
     * Must only be called when isHierarchyFinished returns true.
     */
    private fun evictHierarchy(
        processId: String,
        map: MutableMap<String, AgentProcess>,
        accessOrder: Queue<String>,
    ) {
        findChildrenOf(processId, map).forEach { child ->
            evictHierarchy(child.id, map, accessOrder)
        }
        map.remove(processId)
        accessOrder.remove(processId)
    }

    private fun findChildrenOf(parentId: String, map: Map<String, AgentProcess>): List<AgentProcess> =
        map.values.filter { it.parentId == parentId }
}
