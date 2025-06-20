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
package com.embabel.agent.spi

import com.embabel.agent.core.AgentProcess

/**
 * Spring-style repository for agent processes.
 * We don't extend Repository because it confuses
 * Spring's component scanning if JPA/Neo or another Spring Data
 * solution is used on the classpath downstream.
 */
interface AgentProcessRepository {

    fun findById(id: String): AgentProcess?

    fun save(agentProcess: AgentProcess): AgentProcess

    fun delete(agentProcess: AgentProcess)
}
