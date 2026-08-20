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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Script execution engine that runs scripts inside a Docker container for sandboxed execution.
 *
 * This provides isolation from the host system while still allowing scripts to:
 * - Read input files via INPUT_DIR
 * - Write output artifacts via OUTPUT_DIR
 * - Access network (can be disabled)
 *
 * All staging, I/O, timeout, and artifact logic is inherited from
 * [AbstractContainerSkillScriptExecutionEngine]; this class only supplies Docker-specific
 * details (the `docker` CLI, `--workdir` support, daemon-running hint).
 *
 * @param image the Docker image to use for execution
 * @param timeout maximum execution time before killing the container
 * @param supportedLanguages which script languages this engine supports
 * @param networkEnabled whether to allow network access from the container
 * @param memoryLimit memory limit for the container (e.g., [MemorySize.megabytes] (512)), passed to `--memory`
 * @param cpuLimit CPU limit for the container as a number of cores (e.g., [CpuLimit.cores] (1)), passed to `--cpus`
 * @param environment additional environment variables to pass to the container
 * @param workDir working directory inside the container
 * @param user user to run as inside the container (default: "agent" for the embabel image)
 * @param fileTools FileReadTools for resolving input file paths securely.
 *                  Input paths are resolved relative to the fileTools root with path traversal protection.
 *                  Defaults to current working directory.
 */
class DockerSkillScriptExecutionEngine @JvmOverloads constructor(
    image: String = DEFAULT_IMAGE,
    timeout: Duration = 60.seconds,
    supportedLanguages: Set<ScriptLanguage> = ScriptLanguage.entries.toSet(),
    networkEnabled: Boolean = true,
    memoryLimit: MemorySize? = MemorySize.megabytes(512),
    cpuLimit: CpuLimit? = CpuLimit.cores(1),
    environment: Map<String, String> = emptyMap(),
    workDir: String = "/home/agent/workspace",
    user: String? = "agent",
    fileTools: FileTools = FileTools.readWrite(System.getProperty("user.dir")),
) : AbstractContainerSkillScriptExecutionEngine(
    image = image,
    timeout = timeout,
    supportedLanguages = supportedLanguages,
    networkEnabled = networkEnabled,
    memoryLimit = memoryLimit,
    cpuLimit = cpuLimit,
    environment = environment,
    workDir = workDir,
    user = user,
    fileTools = fileTools,
) {

    override val containerCommand = "docker"
    override val containerName = "Docker"
    override val tempDirPrefix = "skills-docker-"
    override val daemonErrorMessage = "is the Docker daemon running?"

    // Docker accepts --workdir directly and auto-creates the directory in the image.
    override val useWorkdir = true

    companion object {

        /**
         * Create an engine confined to [root]: input files are resolved against [root]
         * and anything outside it (absolute paths, `..` traversal) is rejected.
         *
         * Java-friendly entry point. Kotlin callers can pass `fileTools` directly via the
         * constructor's named argument; Java cannot reach it (the leading `Duration`
         * value-class parameter makes the deeper constructor overloads synthetic), so this
         * factory exposes the one knob a per-request/multi-tenant caller needs.
         */
        @JvmStatic
        fun confinedTo(root: String): DockerSkillScriptExecutionEngine =
            DockerSkillScriptExecutionEngine(fileTools = FileTools.readWrite(root))

        /** Check if Docker is available on this system. */
        fun isDockerAvailable(): Boolean =
            AbstractContainerSkillScriptExecutionEngine.isAvailable("docker")

        /** Check if a Docker image exists locally. */
        fun imageExists(image: String): Boolean =
            AbstractContainerSkillScriptExecutionEngine.imageExists("docker", image)

        /**
         * Ensure the default sandbox image exists, logging build instructions if not.
         *
         * @return true if the image is ready to use
         */
        fun ensureDefaultImageExists(): Boolean =
            AbstractContainerSkillScriptExecutionEngine.ensureDefaultImageExists(
                containerCommand = "docker",
                containerName = "Docker",
                buildInstructions = """
                    |Docker image '$DEFAULT_IMAGE' not found.
                    |
                    |Build it from the embabel-agent-skills module:
                    |  docker build -t $DEFAULT_IMAGE ./embabel-agent-skills/docker
                    |
                    |Or specify a different image:
                    |  DockerSkillScriptExecutionEngine(image = "your-image:tag")
                    """.trimMargin(),
            )

        /** Create an engine with Python-only support. */
        fun pythonOnly(
            image: String = DEFAULT_IMAGE,
            timeout: Duration = 60.seconds,
        ) = DockerSkillScriptExecutionEngine(
            image = image,
            timeout = timeout,
            supportedLanguages = setOf(ScriptLanguage.PYTHON),
        )

        /** Create an engine with maximum isolation (no network, reduced resources). */
        fun isolated(
            image: String = DEFAULT_IMAGE,
            timeout: Duration = 30.seconds,
        ) = DockerSkillScriptExecutionEngine(
            image = image,
            timeout = timeout,
            networkEnabled = false,
            memoryLimit = MemorySize.megabytes(256),
            cpuLimit = CpuLimit.millicores(500),
        )
    }
}
