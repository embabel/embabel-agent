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

import com.embabel.agent.tools.file.FileTools
import java.nio.file.Files
import java.nio.file.Path

/**
 * Confines skill-script input files to an allowed root and stages produced artifacts so a
 * later skill can reuse them as input.
 *
 * Shared by [ProcessSkillScriptExecutionEngine] and [DockerSkillScriptExecutionEngine] so
 * the confinement + artifact-chaining behaviour lives in one place instead of being
 * copy-pasted (and diverging) per engine.
 *
 * @param userRoot resolves the user's own input files, rejecting path traversal and files
 * outside the root.
 */
internal class ConfinedInputResolver(
    private val userRoot: FileTools,
) {

    /**
     * Dedicated, engine-owned directory where produced artifacts are kept. It is a
     * generated temp dir (never the user's working directory), and it is trusted as an
     * input source so a previous skill's artifact can be reused by the next.
     */
    val artifactsRoot: Path = Files.createTempDirectory("skill-artifacts-")

    private val artifactsRootTools: FileTools = FileTools.readWrite(artifactsRoot.toString())

    /**
     * Resolve an input file against the two allowed roots, in order:
     *
     *  1. [userRoot] — where the user's own files live;
     *  2. the artifacts root — where outputs produced by a previous run are kept, so a
     *     later skill can reuse them as input.
     *
     * Both roots reject path traversal (any path escaping the root).
     *
     * We fall back to root 2 ONLY when root 1 throws [SecurityException]. This relies on
     * [FileTools.resolveAndValidateFile] reporting two failures distinctly:
     *
     *  - [SecurityException]        -> the path is *outside* the root. That means "not a user
     *                                  file", so it is worth retrying against the artifacts root.
     *  - [IllegalArgumentException] -> the path is *inside* the root but the file is missing
     *                                  ("file not found"). That is a genuine error, NOT a
     *                                  wrong-root signal, so we let it propagate unchanged.
     *
     * Consequence: a mere typo on a user-dir path stays a clear "file not found" instead of
     * silently falling through to the artifacts root. A path outside both roots is rejected.
     *
     * @throws SecurityException if the path escapes both roots
     * @throws IllegalArgumentException if the path is inside a root but the file is missing
     */
    fun resolve(path: Path): Path =
        try {
            userRoot.resolveAndValidateFile(path.toString())
        } catch (e: SecurityException) {
            artifactsRootTools.resolveAndValidateFile(path.toString())
        }

    /**
     * Copy the regular files in [outputDir] into a fresh run directory under
     * [artifactsRoot] — so they survive [outputDir]'s cleanup and can be reused as input by
     * a subsequent skill — and return them as [ScriptArtifact]s sorted by name.
     */
    fun stageArtifacts(outputDir: Path): List<ScriptArtifact> {
        if (!Files.isDirectory(outputDir)) return emptyList()
        val files = Files.list(outputDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.toList()
        }
        if (files.isEmpty()) return emptyList()

        val runDir = Files.createTempDirectory(artifactsRoot, "run-")
        return files
            .map { file ->
                val dest = runDir.resolve(file.fileName)
                Files.copy(file, dest)
                ScriptArtifact(
                    name = file.fileName.toString(),
                    path = dest.toAbsolutePath(),
                    mimeType = ScriptArtifact.inferMimeType(file.fileName.toString()),
                    sizeBytes = Files.size(dest),
                )
            }
            .sortedBy { it.name }
    }
}
