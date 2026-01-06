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
package com.embabel.agent.spi

import com.embabel.agent.core.Agent
import com.embabel.agent.core.ProcessOptions
import java.util.*

/**
 * Generate names for agent processes
 */
fun interface AgentProcessIdGenerator {

    /**
     * Generate a process ID for a new AgentProcess for this given agent and process options.
     */
    fun createProcessId(agent: Agent, processOptions: ProcessOptions): String

    companion object {
        val RANDOM: AgentProcessIdGenerator = AgentProcessIdGenerator { agent, processOptions ->
            UUID.randomUUID().toString()
        }
    }

}
