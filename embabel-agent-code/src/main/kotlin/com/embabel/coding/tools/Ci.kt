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
package com.embabel.coding.tools

import com.embabel.agent.tools.DirectoryBased
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.core.types.Timed
import com.embabel.common.core.types.Timestamped
import com.embabel.common.util.loggerFor
import com.embabel.common.util.time
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant

/**
 * Options for build
 * @param buildCommand command to run such as "mvn test"
 * @param streamOutput if true, the output will be streamed to the console
 */
data class BuildOptions(
    val buildCommand: String,
    val streamOutput: Boolean = false,
)

data class BuildStatus(
    val success: Boolean,
    val relevantOutput: String,
)

/**
 * Result of a build command
 * @param status status of the build if we could parse it
 */
data class BuildResult(
    val status: BuildStatus?,
    val rawOutput: String,
    override val timestamp: Instant = Instant.now(),
    override val runningTime: Duration,
) : Timestamped, Timed, PromptContributor {

    override fun contribution(): String =
        """
            |Build result: success=${status?.success ?: "unknown"}
            |Relevant output:
            |${relevantOutput()}
        """.trimIndent()

    fun relevantOutput(): String {
        return status?.relevantOutput ?: rawOutput
    }
}


/**
 * CI support with pluggable build systems
 */
class Ci(
    override val root: String,
    val buildSystemIntegrations: List<BuildSystemIntegration> = listOf(
        MavenBuildSystemIntegration()
    ),
) : DirectoryBased {

    private val logger = LoggerFactory.getLogger(Ci::class.java)

    /**
     * Build the project with the given command and parse the output
     * Parse status if known
     */
    fun buildAndParse(buildOptions: BuildOptions): BuildResult {
        val (rawOutput, ms) = time {
            build(buildOptions)
        }
        val buildResult = BuildResult(
            status = null,
            rawOutput = rawOutput,
            runningTime = Duration.ofMillis(ms),
        )
        val buildStatus = parseOutput(buildResult.rawOutput)
        return buildResult.copy(status = buildStatus)
    }

    fun parseBuildOutput(
        rawOutput: String,
        runningTime: Duration,
    ): BuildResult {
        val buildResult = BuildResult(
            status = null,
            rawOutput = rawOutput,
            runningTime = runningTime,
        )
        val buildStatus = parseOutput(buildResult.rawOutput)
        return buildResult.copy(status = buildStatus)
    }

    private fun parseOutput(rawOutput: String): BuildStatus? {
        for (b in buildSystemIntegrations) {
            val status = b.parseBuildOutput(root, rawOutput)
            if (status != null) {
                return status
            }
        }
        logger.warn("No build system understands this output")
        return null
    }

    /**
     * Build the project using the given command
     */
    fun build(buildOptions: BuildOptions): String {
        logger.info("Running build command <{}> in root directory {}", buildOptions.buildCommand, root)

        val processBuilder = ProcessBuilder()

        // Set the working directory to the root
        processBuilder.directory(Paths.get(root).toFile())

        // Configure the command
        val commandParts = buildOptions.buildCommand.split("\\s+".toRegex())
        processBuilder.command(commandParts)

        // Redirect error stream to output stream
        processBuilder.redirectErrorStream(true)

        try {
            val process = processBuilder.start()

            val outputBuilder = StringBuilder()

            // Handle the output differently based on streamOutput flag
            if (buildOptions.streamOutput) {
                // Stream the output to the console while also capturing it
                process.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        println(line)
                        outputBuilder.append(line).append("\n")
                    }
                }
            } else {
                // Original behavior - just capture the output
                val output = process.inputStream.bufferedReader().use { it.readText() }
                outputBuilder.append(output)
            }

            val exitCode = process.waitFor()
            val output = outputBuilder.toString()

            return if (exitCode == 0) {
                "Command executed successfully:\n$output"
            } else {
                "Command failed with exit code $exitCode:\n$output"
            }
        } catch (e: Exception) {
            loggerFor<CiTools>().error("Error executing command: ${buildOptions.buildCommand}", e)
            return "Error executing command: ${e.message}"
        }
    }

}

interface BuildSystemIntegration {

    /**
     * If possible, parse the build result
     */
    fun parseBuildOutput(
        root: String,
        rawOutput: String,
    ): BuildStatus?
}
