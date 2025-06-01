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

import org.springframework.stereotype.Service
import java.io.File
import java.util.concurrent.TimeUnit

fun interface SlideFormatter {

    fun createHtmlSlides(
        directory: String,
        markdownFilename: String
    ): String
}

@Service
class MarpCliSlideFormatter : SlideFormatter {

    override fun createHtmlSlides(directory: String, markdownFileName: String): String {
        runMarpCli(directory, markdownFileName)
        return markdownFileName.replace(".md", ".html")
    }

    fun runMarpCli(directory: String, markdownFileName: String) {
        val command = "npx @marp-team/marp-cli@latest $markdownFileName --no-stdin"
        val processBuilder = ProcessBuilder()

        // Set the working directory
        processBuilder.directory(File(directory))

        // Set the command based on the operating system
        val finalCommand = if (System.getProperty("os.name").lowercase().contains("windows")) {
            listOf("cmd.exe", "/c", command)
        } else {
            listOf("sh", "-c", command)
        }

        processBuilder.command(finalCommand)

        // Redirect error stream to output stream
        processBuilder.redirectErrorStream(true)

        try {
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
                println("Marp CLI completed with exit code: ${process.exitValue()}")
            } else {
                println("Marp CLI process timed out")
                process.destroyForcibly()
            }
        } catch (e: Exception) {
            println("Error executing Marp CLI: ${e.message}")
            e.printStackTrace()
        }
    }

}
