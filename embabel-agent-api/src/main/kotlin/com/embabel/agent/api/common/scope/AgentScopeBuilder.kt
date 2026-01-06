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
package com.embabel.agent.api.common.scope

import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentScope

/**
 * Implement by types that can emit agent scope
 */
interface AgentScopeBuilder {

    /**
     * Emit an AgentScope
     */
    fun createAgentScope(): AgentScope

    companion object {

        /**
         * Create an AgentScopeBuilder from an instance of a class annotated with @Agent or @EmbabelComponent
         */
        @JvmStatic
        fun fromInstance(instance: Any): AgentScopeBuilder {
            return FromInstanceAgentScopeBuilder(instance)
        }

        /**
         * Create an AgentScopeBuilder from multiple instances of classes annotated with @Agent or @EmbabelComponent.
         * Combines all actions, goals, and conditions from the provided instances.
         */
        @JvmStatic
        fun fromInstances(vararg instances: Any): AgentScopeBuilder {
            return FromInstancesAgentScopeBuilder(instances.toList())
        }
    }
}

private class FromInstanceAgentScopeBuilder(
    private val instance: Any,
) : AgentScopeBuilder {

    override fun createAgentScope(): AgentScope {
        return AgentMetadataReader().createAgentMetadata(instance)
            ?: throw IllegalArgumentException("$instance does not have agent metadata: @Agent or @EmbabelComponent annotation required")
    }
}

private class FromInstancesAgentScopeBuilder(
    private val instances: List<Any>,
) : AgentScopeBuilder {

    override fun createAgentScope(): AgentScope {
        val reader = AgentMetadataReader()
        val scopes = instances.map { instance ->
            reader.createAgentMetadata(instance)
                ?: throw IllegalArgumentException("$instance does not have agent metadata: @Agent or @EmbabelComponent annotation required")
        }

        return AgentScope(
            name = "combined-scope",
            description = "Combined scope from ${instances.size} instances",
            actions = scopes.flatMap { it.actions },
            goals = scopes.flatMap { it.goals }.toSet(),
            conditions = scopes.flatMap { it.conditions }.toSet(),
        )
    }
}

private class FromPlatformAgentScopeBuilder(
    private val agentPlatform: AgentPlatform,
) : AgentScopeBuilder {

    override fun createAgentScope(): AgentScope {
        return agentPlatform
    }
}
