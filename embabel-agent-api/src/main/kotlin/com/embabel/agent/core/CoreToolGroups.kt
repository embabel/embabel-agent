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
package com.embabel.agent.core

/**
 * Core tool groups exposed by the platform
 * These should be supported in any AgentPlatform instance.
 */
object CoreToolGroups {

    const val WEB = "web"

    val WEB_DESCRIPTION = ToolGroupDescription(
        description = "Tools for web search and scraping",
        role = WEB,
    )

    const val MATH = "math"

    val MATH_DESCRIPTION = ToolGroupDescription(
        description = "Math tools: use when you need to perform calculations",
        role = MATH,
    )

    const val MAPS = "maps"

    val MAPS_DESCRIPTION = ToolGroupDescription(
        description = "Mapping tools",
        role = MAPS,
    )

    const val GITHUB = "github"

    val GITHUB_DESCRIPTION = ToolGroupDescription(
        description = "Integration with GitHub APIs",
        role = GITHUB,
    )

    const val BROWSER_AUTOMATION = "browser_automation"

    val BROWSER_AUTOMATION_DESCRIPTION = ToolGroupDescription(
        description = "Browser automation tools",
        role = BROWSER_AUTOMATION,
    )
}
