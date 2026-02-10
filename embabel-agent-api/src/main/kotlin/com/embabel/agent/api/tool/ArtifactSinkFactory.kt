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
 * Factory interface for creating artifact-sinking tool decorators.
 * Extended by [Tool.Companion] to provide [Tool.sinkArtifacts], [Tool.publishToBlackboard], etc.
 */
interface ArtifactSinkFactory {

    /**
     * Wrap a tool to sink artifacts of the specified type to the given sink.
     * Handles both single artifacts and Iterables.
     *
     * @param tool The tool to wrap
     * @param clazz The class of artifacts to capture
     * @param sink Where to send captured artifacts
     */
    fun <T : Any> sinkArtifacts(
        tool: Tool,
        clazz: Class<T>,
        sink: ArtifactSink,
    ): Tool = ArtifactSinkingTool(
        delegate = tool,
        clazz = clazz,
        sink = sink,
    )

    /**
     * Wrap a tool to sink artifacts of the specified type to the given sink,
     * with optional filtering and transformation.
     *
     * @param tool The tool to wrap
     * @param clazz The class of artifacts to capture
     * @param sink Where to send captured artifacts
     * @param filter Predicate to filter which artifacts to capture
     * @param transform Function to transform artifacts before sinking
     */
    fun <T : Any> sinkArtifacts(
        tool: Tool,
        clazz: Class<T>,
        sink: ArtifactSink,
        filter: (T) -> Boolean,
        transform: (T) -> Any,
    ): Tool = ArtifactSinkingTool(
        delegate = tool,
        clazz = clazz,
        sink = sink,
        filter = filter,
        transform = transform,
    )

    /**
     * Wrap a tool to publish all artifacts to the blackboard.
     *
     * @param tool The tool to wrap
     */
    fun publishToBlackboard(tool: Tool): Tool = ArtifactSinkingTool(
        delegate = tool,
        clazz = Any::class.java,
        sink = BlackboardSink,
    )

    /**
     * Wrap a tool to publish artifacts of the specified type to the blackboard.
     *
     * @param tool The tool to wrap
     * @param clazz The class of artifacts to publish
     */
    fun <T : Any> publishToBlackboard(
        tool: Tool,
        clazz: Class<T>,
    ): Tool = ArtifactSinkingTool(
        delegate = tool,
        clazz = clazz,
        sink = BlackboardSink,
    )

    /**
     * Wrap a tool to publish artifacts of the specified type to the blackboard,
     * with optional filtering and transformation.
     *
     * @param tool The tool to wrap
     * @param clazz The class of artifacts to publish
     * @param filter Predicate to filter which artifacts to publish
     * @param transform Function to transform artifacts before publishing
     */
    fun <T : Any> publishToBlackboard(
        tool: Tool,
        clazz: Class<T>,
        filter: (T) -> Boolean,
        transform: (T) -> Any,
    ): Tool = ArtifactSinkingTool(
        delegate = tool,
        clazz = clazz,
        sink = BlackboardSink,
        filter = filter,
        transform = transform,
    )
}
