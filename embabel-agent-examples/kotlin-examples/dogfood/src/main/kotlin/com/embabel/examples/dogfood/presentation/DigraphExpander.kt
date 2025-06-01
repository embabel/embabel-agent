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
package com.embabel.examples.dogfood.presentation

import com.embabel.common.util.loggerFor
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

interface DigraphExpander {

    /**
     * Expands a DOT diagram into a more detailed representation.
     *
     * @param fileBase The base name of the file to save the expanded diagram.
     * @param dot The DOT diagram as a string.
     * @return name of the file, without a path
     */
    fun expandDiagram(
        fileBase: String,
        dot: String
    ): String

}

class DotCliDigraphExpander(
    private val directory: String,
) : DigraphExpander {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun expandDiagram(
        fileBase: String,
        dot: String
    ): String {
        val outputFile = "${fileBase}.svg"

        logger.info("Expanding diagram to {}:\n{}", outputFile, dot)

        val command = "echo '$dot' | dot -Tsvg -o $outputFile"
        val processBuilder = ProcessBuilder()

        // Set the working directory
        processBuilder.directory(File(directory))

        // Set the command based on the operating system
        val finalCommand = if (System.getProperty("os.name").lowercase().contains("windows")) {
            listOf("cmd.exe", "/c", command)
        } else {
            listOf("sh", "-c", command)
        }

        loggerFor<DotCliDigraphExpander>().info("Running command {}", finalCommand)
        processBuilder.command(finalCommand)

        // Redirect error stream to output stream
        processBuilder.redirectErrorStream(true)

        // Start the process
        val process = processBuilder.start()

        // Read the output
        val reader = process.inputStream.bufferedReader()
        var line: String?

        while (reader.readLine().also { line = it } != null) {
            println(line)
        }

        // Wait for the process to complete
        val exitCode = process.waitFor(60, TimeUnit.SECONDS)

        if (exitCode) {
            println("dot CLI completed with exit code: ${process.exitValue()}")
        } else {
            println("dot CLI process timed out")
            process.destroyForcibly()
        }
        return "${fileBase}.svg"
    }
}
