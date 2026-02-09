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
package com.embabel.agent.api.tool.progressive

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.AgentProcess

/**
 * A tool implementing progressive disclosure - reveals additional
 * capabilities beyond its initial interface.
 *
 * Progressive tools present a simplified interface to the LLM initially,
 * then reveal more specific tools when invoked. This reduces cognitive
 * load and allows the LLM to discover capabilities on demand.
 *
 * Implementations may vary what tools are revealed based on the current
 * agent process context (permissions, state, phase, etc.).
 *
 * @see UnfoldingTool for a fixed set of inner tools
 */
interface ProgressiveTool : Tool {

    /**
     * The inner tools available in this context.
     *
     * May vary based on process state, permissions, phase, etc.
     * Implementations should return an empty list if no tools
     * are available in the current context.
     *
     * @param process The current agent process context
     * @return The tools available for this process
     */
    fun innerTools(process: AgentProcess): List<Tool>
}
