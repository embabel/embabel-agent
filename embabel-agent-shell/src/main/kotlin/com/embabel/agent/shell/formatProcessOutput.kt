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
package com.embabel.agent.shell

import com.embabel.agent.api.common.DynamicExecutionResult
import com.embabel.agent.domain.library.HasContent
import com.embabel.agent.domain.library.InternetResources
import com.embabel.agent.event.logging.personality.ColorPalette
import com.embabel.common.util.color
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.commons.text.WordUtils

/**
 * Format the output of a process for display to the console.
 * Displays well-known types appropriately; otherwise pretty prints JSON.
 * Displays usage and cost information.
 */
fun formatProcessOutput(
    result: DynamicExecutionResult,
    colorPalette: ColorPalette,
    objectMapper: ObjectMapper,
): String {
    var output = ""
    val resultOutput = result.output

    if (resultOutput is HasContent) {
        // TODO naive Markdown test
        output += if (resultOutput.content.contains("#")) {
            "\n" + markdownToConsole(resultOutput.content)
                .color(colorPalette.color2)
        } else {
            WordUtils.wrap(resultOutput.content, 140).color(
                colorPalette.color2,
            )
        }

        if (resultOutput is InternetResources) {
            output += "\n\n" + resultOutput.links.joinToString("\n") {
                "- ${it.url}: ${
                    it.summary.color(
                        colorPalette.color2
                    )
                }"
            }
        }
    } else {
        output = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
            resultOutput
        )
    }
    return """|
            |
            |You asked: ${result.basis.toString().color(colorPalette.highlight)}
            |
            |${output.color(colorPalette.color2)}
            |
            |${result.agentProcess.costInfoString(verbose = true)}
            |""".trimMargin()
}
