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

/**
 * A [Tool] whose inner tools can be enumerated without an
 * [com.embabel.agent.core.AgentProcess]. The minimal abstraction for
 * "this tool is a facade over a fixed set of inner tools" — used by
 * out-of-process consumers (REST gateway, typed-surface generators,
 * documentation, tree-printers) that have no agent-process scope yet
 * still need the children for namespacing or display.
 *
 * Sits *below* [ProgressiveTool] in the type hierarchy: every
 * [ProgressiveTool] also has an out-of-process inner-tools view via
 * `innerTools` so callers that don't have a process don't have to
 * pass a synthetic one. [UnfoldingTool] inherits `innerTools` from
 * here unchanged.
 *
 * Implement directly when a tool exposes a fixed inner-tool set but
 * doesn't want the chat-loop revelation semantics of
 * [UnfoldingTool] (e.g. agentic tools that run their own inner LLM
 * loop and shouldn't have their parent replaced in the LLM toolset).
 */
interface NestedTool : Tool {

    /**
     * The inner tools this facade exposes. Must be safe to call
     * without an [com.embabel.agent.core.AgentProcess] in scope.
     */
    val innerTools: List<Tool>
}
