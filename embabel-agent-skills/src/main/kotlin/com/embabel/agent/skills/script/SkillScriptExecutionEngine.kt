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

import java.nio.file.Path

/**
 * Engine for executing skill scripts.
 *
 * Implementations of this interface provide different sandboxing strategies
 * for safe script execution. The engine is responsible for:
 * - Validating that the script can be executed
 * - Running the script in an isolated environment
 * - Capturing output and exit codes
 * - Enforcing resource limits (timeouts, memory, etc.)
 *
 * ## Sandboxing Strategies
 *
 * Different implementations may provide varying levels of isolation:
 * - [NoOpExecutionEngine]: Denies all execution (safe default)
 * - [ProcessSkillScriptExecutionEngine]: Runs scripts as subprocesses with OS-level isolation
 * - [DockerSkillScriptExecutionEngine]: Runs scripts in ephemeral containers (strongest isolation)
 *
 * @see ScriptExecutionResult
 * @see SkillScript
 */
interface SkillScriptExecutionEngine {

    /**
     * The set of script languages this engine can execute.
     */
    fun supportedLanguages(): Set<ScriptLanguage>

    /**
     * Execute a script.
     *
     * Scripts have access to two environment variables for file I/O:
     * - `INPUT_DIR`: Directory containing input files (copied from [inputFiles])
     * - `OUTPUT_DIR`: Directory where scripts should write output artifacts
     *
     * @param script the script to execute
     * @param args arguments to pass to the script
     * @param stdin input to provide via standard input
     * @param inputFiles paths to files that should be made available in INPUT_DIR
     * @return the execution result
     */
    fun execute(
        script: SkillScript,
        args: List<String> = emptyList(),
        stdin: String? = null,
        inputFiles: List<Path> = emptyList(),
    ): ScriptExecutionResult

    /**
     * Check if a script can be executed without actually running it.
     *
     * @param script the script to validate
     * @return null if execution is allowed, or a Denied result with reason
     */
    fun validate(script: SkillScript): ScriptExecutionResult.Denied? {
        if (script.language !in supportedLanguages()) {
            return ScriptExecutionResult.Denied(
                "Language ${script.language} is not supported by this engine. " +
                    "Supported: ${supportedLanguages().joinToString()}"
            )
        }
        return null
    }
}

/**
 * A no-op execution engine that denies all script execution.
 *
 * This is the safe default when no sandbox is configured.
 * Scripts can still be read via [LoadedSkill.readResource], but not executed.
 */
object NoOpExecutionEngine : SkillScriptExecutionEngine {

    override fun supportedLanguages(): Set<ScriptLanguage> = emptySet()

    override fun execute(
        script: SkillScript,
        args: List<String>,
        stdin: String?,
        inputFiles: List<Path>,
    ): ScriptExecutionResult = ScriptExecutionResult.Denied(
        "Script execution is disabled. No execution engine is configured."
    )

    override fun validate(script: SkillScript): ScriptExecutionResult.Denied =
        ScriptExecutionResult.Denied(
            "Script execution is disabled. No execution engine is configured."
        )
}
