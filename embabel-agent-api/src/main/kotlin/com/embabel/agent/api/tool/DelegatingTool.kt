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
 * The default [call] (String, ToolCallContext) implementation propagates
 * context through the decorator chain by delegating to
 * `delegate.call(input, context)`. Decorators that add behavior
 * (e.g., artifact sinking, replanning) should override this method
 * to apply their logic while preserving context propagation.
 */
interface DelegatingTool : Tool {

    /**
     * The underlying tool being delegated to.
     */
    val delegate: Tool

    /**
     * Propagates [context] through the decorator chain.
     * Decorators that override [call] (String) to add behavior should
     * also override this method to apply the same behavior while
     * forwarding context to [delegate].
     */
    override fun call(input: String, context: ToolCallContext): Tool.Result =
        delegate.call(input, context)
}
