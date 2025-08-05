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
package com.embabel.agent.core.support

import com.embabel.agent.api.common.ToolObject
import org.springframework.ai.support.ToolCallbacks
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.DefaultToolDefinition
import org.springframework.ai.tool.definition.ToolDefinition

/**
 * ToolCallbacks.from complains if no tools.
 * Also conceal varargs
 */
fun safelyGetToolCallbacks(instances: Collection<ToolObject>): List<ToolCallback> =
    instances.flatMap { safelyGetToolCallbacksFrom(it) }
        .distinctBy { it.toolDefinition.name() }
        .sortedBy { it.toolDefinition.name() }


fun safelyGetToolCallbacksFrom(toolObject: ToolObject): List<ToolCallback> {
    val callbacks = mutableListOf<ToolCallback>()
    try {
        when (toolObject.obj) {
            is ToolCallback -> callbacks.add(toolObject.obj)
            else ->
                callbacks.addAll(ToolCallbacks.from(toolObject.obj).toList())
        }
    } catch (_: IllegalStateException) {
        // Ignore this exception from Spring AI.
        // Passing in object without @Tool annotations is not a problem:
        // it should simply be ignored
    }
    return callbacks
        .filter { toolObject.filter(it.toolDefinition.name()) }
        .map {
            val newName = toolObject.namingStrategy.transform(it.toolDefinition.name())
            if (newName != it.toolDefinition.name()) {
                RenamedToolCallback(it, newName)
            } else {
                it
            }
        }
        .distinctBy { it.toolDefinition.name() }
        .sortedBy { it.toolDefinition.name() }
}

/**
 * Allows renaming a ToolCallback
 */
class RenamedToolCallback(
    private val delegate: ToolCallback,
    private val newName: String,
) : ToolCallback {

    override fun getToolDefinition(): ToolDefinition =
        DefaultToolDefinition(newName, delegate.toolDefinition.description(), delegate.toolDefinition.inputSchema())

    override fun call(toolInput: String): String = delegate.call(toolInput)
}
