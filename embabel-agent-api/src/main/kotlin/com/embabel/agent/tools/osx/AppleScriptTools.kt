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
package com.embabel.agent.tools.osx

import com.embabel.agent.api.common.support.SelfToolGroup
import com.embabel.agent.common.Constants
import com.embabel.agent.core.ToolGroupDescription
import com.embabel.agent.core.ToolGroupPermission
import com.embabel.common.core.types.Semver
import com.embabel.common.util.MacOSCondition
import org.springframework.ai.tool.annotation.Tool
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * Simple AppleScript integration for automation on OS/X
 */
@Service
@Profile("!test")
@Conditional(MacOSCondition::class)
class AppleScriptTools : SelfToolGroup {

    override val version = Semver(0, 1, 0)

    override val provider = Constants.EMBABEL_PROVIDER

    override val description: ToolGroupDescription
        get() = ToolGroupDescription(role = "AppleScript", description = "Run AppleScript commands")

    override val permissions: Set<ToolGroupPermission>
        get() = setOf(ToolGroupPermission.HOST_ACCESS)

    @Tool(description = "Run AppleScript command")
    fun runAppleScript(script: String) {
        val runtime = Runtime.getRuntime()
        val process = runtime.exec(arrayOf("osascript", "-e", script))
        // You can handle output/errors if needed
        val exitCode = process.waitFor()
        println("Script executed with exit code: $exitCode")
    }
}
