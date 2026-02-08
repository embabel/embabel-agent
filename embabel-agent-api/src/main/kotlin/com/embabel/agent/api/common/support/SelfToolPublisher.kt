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
package com.embabel.agent.api.common.support

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.ToolObject
import com.embabel.agent.core.ToolGroup
import com.embabel.agent.core.ToolGroupDescription
import com.embabel.agent.core.ToolGroupMetadata
import com.embabel.agent.core.ToolGroupPermission
import com.embabel.agent.core.ToolPublisher
import com.embabel.agent.core.support.safelyGetToolsFrom
import com.embabel.common.core.types.AssetCoordinates
import com.embabel.common.core.types.Semver

/**
 * Convenient interface a class can implement to publish @Tool
 * functions automatically. Application domain objects can extend this.
 * Intended for extension by both platform and application code.
 */
interface SelfToolPublisher : ToolPublisher {

    override val tools: List<Tool>
        get() = safelyGetToolsFrom(ToolObject(this))
}

/**
 * A ToolGroup that publishes its own @Tool annotated methods.
 * Implements ToolGroup using native Tool interface (not Spring AI ToolCallbacks).
 */
interface SelfToolGroup : SelfToolPublisher, ToolGroup, AssetCoordinates {

    val description: ToolGroupDescription

    override val name: String get() = javaClass.name

    override val provider: String

    override val version: Semver

    val permissions: Set<ToolGroupPermission>

    override val metadata: ToolGroupMetadata
        get() = ToolGroupMetadata(
            description = description,
            name = name,
            provider = provider,
            permissions = permissions,
            version = version,
        )

    // Explicitly inherit tools from SelfToolPublisher
    override val tools: List<Tool>
        get() = safelyGetToolsFrom(ToolObject(this))

}
