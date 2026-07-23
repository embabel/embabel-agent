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
package com.embabel.agent.skills.script

import com.embabel.agent.api.tool.Tool
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Path
import java.nio.file.Paths

/**
 * A Tool that executes a skill script.
 *
 * This wraps a [SkillScript] and a [SkillScriptExecutionEngine] to expose
 * the script as a callable tool for LLMs.
 *
 * @param script the script to execute
 * @param engine the execution engine providing sandboxing
 * @param description optional custom description (defaults to auto-generated)
 */
class ScriptTool(
    val script: SkillScript,
    private val engine: SkillScriptExecutionEngine,
    description: String? = null,
) : Tool {

    private val objectMapper = jacksonObjectMapper()

    override val definition: Tool.Definition = Tool.Definition(
        name = script.toolName,
        description = description
            ?: "Execute the ${script.fileName} script from the ${script.skillName} skill",
        inputSchema = Tool.InputSchema.of(
            Tool.Parameter(
                name = "args",
                type = Tool.ParameterType.ARRAY,
                description = "Arguments to pass to the script",
                required = false,
                itemType = Tool.ParameterType.STRING,
            ),
            Tool.Parameter.string(
                name = "stdin",
                description = "Input to provide via standard input",
                required = false,
            ),
            Tool.Parameter(
                name = "inputFiles",
                type = Tool.ParameterType.ARRAY,
                description = "Paths to files to make available to the script in INPUT_DIR",
                required = false,
                itemType = Tool.ParameterType.STRING,
            ),
        ),
    )

    override fun call(input: String): Tool.Result {
        // Validate before execution
        engine.validate(script)?.let { denied ->
            return Tool.Result.error(denied.reason)
        }

        // Parse input
        val params = parseInput(input)

        // Convert input file paths to Path objects
        val inputFilePaths = params.inputFiles.map { Paths.get(it) }

        // Execute
        val result = engine.execute(script, params.args, params.stdin, inputFilePaths)

        return formatResult(result)
    }

    private fun parseInput(input: String): ScriptInput {
        if (input.isBlank()) {
            return ScriptInput()
        }

        return try {
            objectMapper.readValue<ScriptInput>(input)
        } catch (e: Exception) {
            ScriptInput()
        }
    }

    private fun formatResult(result: ScriptExecutionResult): Tool.Result = when (result) {
        is ScriptExecutionResult.Success -> {
            val output = buildString {
                appendLine("Script completed with exit code ${result.exitCode}")
                appendLine("Duration: ${result.duration}")
                if (result.stdout.isNotBlank()) {
                    appendLine()
                    appendLine("=== stdout ===")
                    appendLine(result.stdout.trim())
                }
                if (result.stderr.isNotBlank()) {
                    appendLine()
                    appendLine("=== stderr ===")
                    appendLine(result.stderr.trim())
                }
                if (result.artifacts.isNotEmpty()) {
                    appendLine()
                    appendLine("=== artifacts ===")
                    result.artifacts.forEach { artifact ->
                        val size = formatFileSize(artifact.sizeBytes)
                        val type = artifact.mimeType ?: "unknown"
                        appendLine("- ${artifact.name} ($type, $size)")
                        appendLine("  Path: ${artifact.path}")
                    }
                }
            }

            if (result.artifacts.isNotEmpty()) {
                Tool.Result.withArtifact(output.trim(), result.artifacts)
            } else {
                Tool.Result.text(output.trim())
            }
        }

        is ScriptExecutionResult.Failure -> {
            val message = buildString {
                append("Script failed: ${result.error}")
                if (result.timedOut) {
                    append(" (timed out)")
                }
                result.exitCode?.let { append(" [exit code: $it]") }
                result.duration?.let { append(" [ran for: $it]") }
                if (!result.stderr.isNullOrBlank()) {
                    appendLine()
                    appendLine("=== stderr ===")
                    appendLine(result.stderr.trim())
                }
            }
            Tool.Result.error(message.trim())
        }

        is ScriptExecutionResult.Denied -> {
            Tool.Result.error("Execution denied: ${result.reason}")
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    /**
     * Input parameters for script execution.
     */
    private data class ScriptInput(
        val args: List<String> = emptyList(),
        val stdin: String? = null,
        val inputFiles: List<String> = emptyList(),
    )
}
