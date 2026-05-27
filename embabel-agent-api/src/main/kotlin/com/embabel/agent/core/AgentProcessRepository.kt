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
package com.embabel.agent.core

/**
 * Repository for agent processes.
 */
interface AgentProcessRepository {

    fun findById(id: String): AgentProcess?

    /**
     * Find all child processes for the given parent process ID.
     * @param parentId the ID of the parent process
     * @return list of child processes, empty if none found
     */
    fun findByParentId(parentId: String): List<AgentProcess>

    /**
     * Save a new agent process.
     */
    fun save(agentProcess: AgentProcess): AgentProcess

    /**
     * Update an existing agent process.
     */
    fun update(agentProcess: AgentProcess)

    fun delete(agentProcess: AgentProcess)
}

/**
 * Abstract base class for AgentProcessRepository implementations that enforces
 * ephemeral process handling.
 *
 * Ephemeral processes (marked with `processOptions.ephemeral = true`) are not persisted
 * and cannot spawn child processes. This class uses the template method pattern to
 * prevent persistence of ephemeral processes while allowing implementations to
 * customize actual persistence logic.
 *
 * Implementations should:
 * - Extend this class instead of implementing AgentProcessRepository directly
 * - Override [doSave] and [doUpdate] instead of [save] and [update]
 * - The final [save] and [update] methods will handle ephemeral checks automatically
 */
abstract class AbstractAgentProcessRepository : AgentProcessRepository {

    private val logger = org.slf4j.LoggerFactory.getLogger(javaClass)

    /**
     * Save a new agent process.
     * This method checks if the process is ephemeral and logs an error if persistence
     * is attempted. Non-ephemeral processes are delegated to [doSave].
     */
    final override fun save(agentProcess: AgentProcess): AgentProcess {
        if (agentProcess.processOptions.ephemeral) {
            logger.error(
                """
                Attempted to save ephemeral AgentProcess [id={}].
                Ephemeral processes are not persisted and do not support wait states.
                Operation skipped.
                """.trimIndent(),
                agentProcess.id
            )
            return agentProcess
        }
        return doSave(agentProcess)
    }

    /**
     * Update an existing agent process.
     * This method checks if the process is ephemeral and logs an error if persistence
     * is attempted. Non-ephemeral processes are delegated to [doUpdate].
     */
    final override fun update(agentProcess: AgentProcess) {
        if (agentProcess.processOptions.ephemeral) {
            logger.error(
                """
                Attempted to update ephemeral AgentProcess [id={}].
                Ephemeral processes are not persisted.
                Operation skipped.
                """.trimIndent(),
                agentProcess.id
            )
            return
        }
        doUpdate(agentProcess)
    }

    /**
     * Perform the actual save operation for non-ephemeral processes.
     * Implementations should override this method instead of [save].
     */
    protected abstract fun doSave(agentProcess: AgentProcess): AgentProcess

    /**
     * Perform the actual update operation for non-ephemeral processes.
     * Implementations should override this method instead of [update].
     */
    protected abstract fun doUpdate(agentProcess: AgentProcess)
}
