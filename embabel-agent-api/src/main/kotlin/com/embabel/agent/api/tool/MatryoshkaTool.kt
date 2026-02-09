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
package com.embabel.agent.api.tool

import com.embabel.agent.api.tool.progressive.UnfoldingTool

/**
 * A tool that contains other tools, enabling progressive tool disclosure.
 *
 * Named after Russian nesting dolls, a MatryoshkaTool presents a high-level
 * description to the LLM. When invoked, its inner tools become available and
 * (optionally) the MatryoshkaTool itself is removed.
 *
 * This is an alias for [UnfoldingTool] for backward compatibility.
 * New code should use [UnfoldingTool] directly.
 *
 * @see UnfoldingTool
 * @see com.embabel.agent.spi.loop.MatryoshkaToolInjectionStrategy
 */
interface MatryoshkaTool : UnfoldingTool {

    /**
     * Companion object that extends [UnfoldingTool.Factory] to provide
     * factory methods. All factory methods from UnfoldingTool are available.
     *
     * Example:
     * ```kotlin
     * val tool = MatryoshkaTool.of(
     *     name = "spotify_search",
     *     description = "Search Spotify for music data",
     *     innerTools = listOf(vectorSearchTool, textSearchTool)
     * )
     * ```
     */
    companion object : UnfoldingTool.Factory()
}
