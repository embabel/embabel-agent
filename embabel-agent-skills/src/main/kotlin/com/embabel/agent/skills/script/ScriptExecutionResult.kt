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

import com.embabel.agent.api.reference.LlmReference
import com.embabel.chat.MaterializableAsset
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration

/**
 * An artifact produced by script execution.
 *
 * Scripts can produce output files by writing to the directory specified
 * in the `OUTPUT_DIR` environment variable. After execution, files in that
 * directory are collected and returned as artifacts.
 *
 * @param name the file name (e.g., "result.pdf")
 * @param path the absolute path to the artifact file
 * @param mimeType the detected or inferred MIME type, if known
 * @param sizeBytes the size of the file in bytes
 */
data class ScriptArtifact(
    override val name: String,
    val path: Path,
    override val mimeType: String? = null,
    override val sizeBytes: Long,
) : MaterializableAsset {

    override val id: String = UUID.randomUUID().toString()

    override val timestamp: Instant = Instant.now()

    override fun persistent(): Boolean = false

    override fun openStream() = Files.newInputStream(path)

    override fun reference(): LlmReference = LlmReference.of(
        name = name,
        description = "Artifact produced by a skill script",
        tools = emptyList(),
        notes = "MIME type: ${mimeType ?: "unknown"}; size: $sizeBytes bytes; path: $path",
    )

    companion object {
        /**
         * Common MIME types by file extension.
         */
        private val MIME_TYPES = mapOf(
            "pdf" to "application/pdf",
            "json" to "application/json",
            "xml" to "application/xml",
            "txt" to "text/plain",
            "csv" to "text/csv",
            "html" to "text/html",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "svg" to "image/svg+xml",
            "zip" to "application/zip",
            "tar" to "application/x-tar",
            "gz" to "application/gzip",
        )

        /**
         * Infer MIME type from file extension.
         */
        fun inferMimeType(fileName: String): String? {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return MIME_TYPES[ext]
        }
    }
}

/**
 * Result of executing a skill script.
 */
sealed class ScriptExecutionResult {

    /**
     * Successful script execution.
     *
     * @param stdout the standard output from the script
     * @param stderr the standard error from the script
     * @param exitCode the exit code (0 typically indicates success)
     * @param duration how long the script took to execute
     * @param artifacts files produced by the script in the OUTPUT_DIR
     */
    data class Success(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val duration: Duration,
        val artifacts: List<ScriptArtifact> = emptyList(),
    ) : ScriptExecutionResult()

    /**
     * Script execution failed.
     *
     * @param error description of what went wrong
     * @param stderr standard error output, if available
     * @param exitCode exit code, if available
     * @param timedOut true if the script was terminated due to timeout
     * @param duration how long the script ran before failure
     */
    data class Failure(
        val error: String,
        val stderr: String? = null,
        val exitCode: Int? = null,
        val timedOut: Boolean = false,
        val duration: Duration? = null,
    ) : ScriptExecutionResult()

    /**
     * Script execution was denied by the engine.
     *
     * @param reason why execution was denied
     */
    data class Denied(
        val reason: String,
    ) : ScriptExecutionResult()
}
