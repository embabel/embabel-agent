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

/**
 * Interface for tool decorators that wrap another tool.
 * Enables unwrapping to find the underlying tool implementation.
 * Thus, it is important that tool wrappers implement this interface to allow unwrapping.
 *
 * ## Canonical call method
 *
 * [call] (String, ToolCallContext) is the **single canonical entry point** for
 * decorator logic. Decorators should override only this method. The single-arg
 * [call] (String) routes through it automatically via [ToolCallContext.EMPTY],
 * so both call paths execute the same decorator behavior.
 *
 * This eliminates a class of bugs where a decorator overrides [call] (String)
 * but the two-arg variant (used by [com.embabel.agent.spi.loop.support.DefaultToolLoop])
 * bypasses the decorator entirely.
 */
interface DelegatingTool : Tool {

    /**
     * The underlying tool being delegated to.
     */
    val delegate: Tool

    /**
     * Routes single-arg calls through the canonical two-arg method,
     * ensuring decorator logic in [call] (String, ToolCallContext) is
     * always executed regardless of which overload the caller uses.
     */
    override fun call(input: String): Tool.Result =
        call(input, ToolCallContext.EMPTY)

    /**
     * Canonical entry point for decorator logic. Override this method
     * to add behavior while preserving context propagation to [delegate].
     *
     * The default implementation simply forwards to the delegate.
     */
    override fun call(input: String, context: ToolCallContext): Tool.Result =
        delegate.call(input, context)
}
