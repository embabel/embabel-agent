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

class MavenBuildSystemIntegration : BuildSystemIntegration {

    override fun parseBuildOutput(root: String, rawOutput: String): BuildStatus? {

        // TODO messy test
        if (!rawOutput.contains("[INFO]")) {
            // Not a Maven build
            return null
        }
        val success = rawOutput.contains("BUILD SUCCESS")
        val warnings = rawOutput.lines().filter { it.contains("[WARNING]") }.joinToString("\n")
        val errors = rawOutput.lines().filter { it.contains("[ERROR]") }.joinToString("\n")
        val relevantOutput = "$warnings\n$errors"
        return BuildStatus(
            success = success,
            relevantOutput = relevantOutput,
        )
    }
}
