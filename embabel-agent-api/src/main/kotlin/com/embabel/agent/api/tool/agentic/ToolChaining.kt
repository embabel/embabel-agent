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
package com.embabel.agent.api.tool.agentic

/**
 * Fluent API for configuring domain tool chaining.
 *
 * Enables @LlmTool methods on returned domain objects to become available as tools.
 * Shared by [AgenticTool] and [com.embabel.agent.api.common.PromptRunner].
 *
 * @param THIS The concrete implementation type for fluent method chaining
 */
interface ToolChaining<THIS> {

    /**
     * Register a domain class with a predicate to control when its @LlmTool methods
     * become available as tools.
     *
     * When a single artifact of the specified type is returned, any @LlmTool annotated
     * methods on that instance become available as tools, provided the predicate is satisfied.
     *
     * When multiple artifacts of the same type are returned, "last wins" - only the
     * most recent artifact that passes the predicate will have its tools exposed.
     *
     * @param type The domain class that may contribute @LlmTool methods
     * @param predicate Predicate to filter which instances contribute tools
     */
    fun <T : Any> withDomainToolsFrom(
        type: Class<T>,
        predicate: DomainToolPredicate<T>,
    ): THIS

    /**
     * Register a domain class whose @LlmTool methods become available as tools
     * when a single instance of that type is returned as an artifact.
     *
     * This is a convenience method that accepts all instances (no filtering).
     *
     * @param type The domain class that may contribute @LlmTool methods
     */
    fun <T : Any> withDomainToolsFrom(type: Class<T>): THIS =
        withDomainToolsFrom(type, DomainToolPredicate.always())

    /**
     * Enable auto-discovery of domain tools from any returned artifact.
     *
     * When enabled, any artifact with @LlmTool methods will automatically
     * have its tools exposed, replacing ALL previous bindings.
     */
    fun withAnyDomainTools(): THIS
}
