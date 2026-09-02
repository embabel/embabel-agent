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
package com.embabel.agent.spi.support.persistence

import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.spi.persistence.AgentProcessCheckpointPolicy

/**
 * Checkpoints only processes that are parked by WaitFor.
 *
 * This is the default lightweight policy for
 * [issue #1965](https://github.com/embabel/embabel-agent/issues/1965): persist
 * when a process intentionally waits for external input, so another pod can
 * restore and resume it later.
 */
object WaitForCheckpointPolicy : AgentProcessCheckpointPolicy {

    override fun shouldStoreSnapshot(agentProcess: AgentProcess): Boolean =
        agentProcess.status == AgentProcessStatusCode.WAITING
}

/**
 * Checkpoints recovery and lifecycle boundary states.
 *
 * Waiting snapshots allow another runtime to resume work after local process
 * loss. Finished snapshots then advance the durable state after the resumed
 * process completes, fails, or is externally terminated.
 */
object LifecycleCheckpointPolicy : AgentProcessCheckpointPolicy {

    override fun shouldStoreSnapshot(agentProcess: AgentProcess): Boolean =
        agentProcess.status == AgentProcessStatusCode.WAITING || agentProcess.finished
}
