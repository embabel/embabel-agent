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

import com.embabel.agent.core.AgentProcess
import org.slf4j.LoggerFactory

/**
 * Destination for captured artifacts from tool results.
 */
fun interface ArtifactSink {
    fun accept(artifact: Any)
}

/**
 * Sink that publishes artifacts to the current AgentProcess blackboard.
 */
object BlackboardSink : ArtifactSink {
    override fun accept(artifact: Any) {
        val agentProcess = AgentProcess.get()
            ?: throw IllegalStateException("No AgentProcess available for BlackboardSink")
        agentProcess.blackboard.addObject(artifact)
    }
}

/**
 * Sink that collects artifacts into a mutable list.
 */
class ListSink(
    private val target: MutableList<Any> = mutableListOf(),
) : ArtifactSink {
    override fun accept(artifact: Any) {
        target.add(artifact)
    }

    fun items(): List<Any> = target.toList()
}

/**
 * Sink that delegates to multiple sinks.
 */
class CompositeSink(
    private val sinks: List<ArtifactSink>,
) : ArtifactSink {
    constructor(vararg sinks: ArtifactSink) : this(sinks.toList())

    override fun accept(artifact: Any) {
        sinks.forEach { it.accept(artifact) }
    }
}

/**
 * Tool decorator that captures artifacts from tool results, filters and transforms them,
 * then sends to one or more sinks.
 *
 * Handles both single artifacts and Iterables of artifacts.
 *
 * @param T The type of artifact to capture
 * @param delegate The tool to wrap
 * @param clazz The class of T for type filtering
 * @param sink Where to send captured artifacts
 * @param filter Optional filter to decide which artifacts to capture. Default accepts all.
 * @param transform Optional function to transform artifacts before sending to sink. Default passes through.
 */
class ArtifactSinkingTool<T : Any>(
    override val delegate: Tool,
    private val clazz: Class<T>,
    private val sink: ArtifactSink,
    private val filter: (T) -> Boolean = { true },
    private val transform: (T) -> Any = { it },
) : DelegatingTool {

    private val logger = LoggerFactory.getLogger(javaClass)

    override val definition: Tool.Definition = delegate.definition
    override val metadata: Tool.Metadata = delegate.metadata

    override fun call(input: String): Tool.Result =
        callAndSink { delegate.call(input) }

    override fun call(input: String, context: ToolCallContext): Tool.Result =
        callAndSink { delegate.call(input, context) }

    private inline fun callAndSink(action: () -> Tool.Result): Tool.Result {
        val result = action()

        if (result is Tool.Result.WithArtifact) {
            val artifact = result.artifact
            when {
                artifact is Iterable<*> -> {
                    artifact.filterIsInstance(clazz).filter(filter).forEach { item ->
                        emit(item)
                    }
                }
                clazz.isInstance(artifact) -> {
                    @Suppress("UNCHECKED_CAST")
                    val typed = artifact as T
                    if (filter(typed)) {
                        emit(typed)
                    }
                }
            }
        }

        return result
    }

    private fun emit(item: T) {
        val transformed = transform(item)
        sink.accept(transformed)
        logger.debug(
            "Sinking artifact of class {} from tool={}",
            transformed.javaClass.simpleName,
            definition.name,
        )
    }

    override fun toString(): String = "ArtifactSinkingTool(delegate=${delegate.definition.name})"
}
