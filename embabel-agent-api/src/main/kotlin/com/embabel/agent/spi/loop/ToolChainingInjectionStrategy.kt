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
package com.embabel.agent.spi.loop

import com.embabel.agent.api.tool.agentic.DomainToolTracker

/**
 * Injection strategy that drains auto-discovered tools from a [DomainToolTracker]
 * and injects them into the running tool loop.
 *
 * When a tool returns an object with @LlmTool methods in auto-discovery mode,
 * the [DomainToolTracker] buffers the discovered tools. This strategy drains
 * that buffer after each tool call, making the discovered tools available
 * for subsequent iterations.
 */
internal class ToolChainingInjectionStrategy(
    private val tracker: DomainToolTracker,
) : ToolInjectionStrategy {

    override fun evaluate(context: ToolInjectionContext): ToolInjectionResult {
        val newTools = tracker.drainPendingTools()
        return ToolInjectionResult.add(newTools)
    }
}
