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
package com.embabel.agent.spi.config.spring

import com.embabel.agent.spi.persistence.AgentProcessCheckpointPolicy
import com.embabel.agent.spi.support.persistence.LifecycleCheckpointPolicy
import com.embabel.agent.spi.support.persistence.WaitForCheckpointPolicy
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for durable agent process persistence.
 *
 * Persistence activates only when the application also supplies an
 * [com.embabel.agent.spi.persistence.AgentProcessSnapshotStore] bean, so adding a
 * store module is enough to turn it on. [enabled] exists to switch it back off
 * without removing the store.
 */
@ConfigurationProperties("embabel.agent.platform.persistence")
class AgentProcessPersistenceProperties {

    /**
     * Whether to checkpoint agent processes when a snapshot store is available.
     * Set to false to keep processes in memory despite a store being present.
     */
    var enabled: Boolean = true

    /**
     * When a process is written to the snapshot store.
     */
    var checkpointPolicy: CheckpointPolicy = CheckpointPolicy.LIFECYCLE

    /**
     * Resolve the configured policy to its implementation.
     */
    fun resolveCheckpointPolicy(): AgentProcessCheckpointPolicy =
        when (checkpointPolicy) {
            CheckpointPolicy.WAITING -> WaitForCheckpointPolicy
            CheckpointPolicy.LIFECYCLE -> LifecycleCheckpointPolicy
        }

    /**
     * Supported checkpoint boundaries.
     */
    enum class CheckpointPolicy {

        /**
         * Checkpoint only processes parked in `WAITING`. Cheapest, and enough to
         * recover a human-in-the-loop process whose owning node is lost.
         */
        WAITING,

        /**
         * Checkpoint processes that are `WAITING` or finished, so durable state
         * also advances once resumed work completes. The default.
         */
        LIFECYCLE,
    }

    companion object {
        operator fun invoke(
            enabled: Boolean = true,
            checkpointPolicy: CheckpointPolicy = CheckpointPolicy.LIFECYCLE,
        ): AgentProcessPersistenceProperties =
            AgentProcessPersistenceProperties().apply {
                this.enabled = enabled
                this.checkpointPolicy = checkpointPolicy
            }
    }
}
