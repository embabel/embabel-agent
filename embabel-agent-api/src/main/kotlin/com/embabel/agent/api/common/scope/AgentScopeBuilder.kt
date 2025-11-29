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
package com.embabel.agent.api.common.scope

import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentScope

/**
 * Implement by types that can emit agent scope
 */
interface AgentScopeBuilder {

    fun build(): AgentScope

    companion object {

        /**
         * Create an AgentScopeBuilder from an instance of a class annotated with @Agent or @EmbabelComponent
         */
        @JvmStatic
        fun fromInstance(instance: Any): AgentScopeBuilder {
            return FromInstanceAgentScopeBuilder(instance)
        }

        /**
         * Create an AgentScopeBuilder from an AgentPlatform,
         * exposing all actions and goals
         */
        @JvmStatic
        fun fromPlatform(agentPlatform: AgentPlatform): AgentScopeBuilder {
            return FromPlatformAgentScopeBuilder(agentPlatform)
        }
    }
}

private class FromInstanceAgentScopeBuilder(
    private val instance: Any,
) : AgentScopeBuilder {

    override fun build(): AgentScope {
        return AgentMetadataReader().createAgentMetadata(instance)
            ?: throw IllegalArgumentException("$instance does not have agent metadata: @Agent or @EmbabelComponent annotation required")
    }
}

private class FromPlatformAgentScopeBuilder(
    private val agentPlatform: AgentPlatform,
) : AgentScopeBuilder {

    override fun build(): AgentScope {
        return agentPlatform
    }
}
