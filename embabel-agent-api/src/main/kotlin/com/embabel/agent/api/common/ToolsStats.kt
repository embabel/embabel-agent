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
package com.embabel.agent.api.common

import com.embabel.common.core.types.HasInfoString

/**
 * Stats around tool calls to a particular tool.
 * Open to allow implementations to extend if they wish
 * @param name The name of the tool.
 * @param calls The number of times the tool has been called.
 * @param averageResponseTime The average response time of the tool in milliseconds.
 * @param failures The number of times the tool has failed.
 */
open class ToolStats(
    val name: String,
    val calls: Int,
    val averageResponseTime: Long = 0L,
    val failures: Int = 0,
) : HasInfoString {

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String =
        "ToolStats(name=$name, calls=$calls, avgResponseTime=$averageResponseTime ms, failures=$failures)"

}

/**
 * Tool statistics, indexed by tool name.
 */
interface ToolsStats : HasInfoString {

    val toolsStats: Map<String, ToolStats>

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        return toolsStats.entries.joinToString(
            prefix = "Tool usage:\n",
            separator = ",\n"
        ) { (_, toolStats) ->
            "\t${toolStats.infoString(verbose, 1)}"
        }
    }
}
