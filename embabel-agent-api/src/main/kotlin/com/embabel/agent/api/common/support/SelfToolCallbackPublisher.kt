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
package com.embabel.agent.api.common.support

import com.embabel.agent.core.*
import com.embabel.common.core.types.AssetCoordinates
import com.embabel.common.core.types.Semver
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.ToolCallbacks

/**
 * Convenient interface a class can implement to publish @Tool
 * functions automatically. Application domain objects can extend this.
 * Intended for extension by both platform and application code.
 */
interface SelfToolCallbackPublisher : ToolCallbackPublisher {

    override val toolCallbacks: List<ToolCallback>
        get() = ToolCallbacks.from(this).toList()
}

interface SelfToolGroup : SelfToolCallbackPublisher, ToolGroup, AssetCoordinates {

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
}
