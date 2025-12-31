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
package com.embabel.coding.tools.bash

import org.springframework.ai.tool.annotation.Tool
import java.io.File

class BashTools(private val workingDirectory: String) {

    @Tool(description = "Generic bash tool")
    fun runBashCommand(command: String): String {
        val process = ProcessBuilder("/bin/bash", "-c", command)
            .directory(File(workingDirectory))
            .redirectErrorStream(true)
            .start()
        return process.inputStream.bufferedReader().readText()
    }
}
