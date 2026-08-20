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
 * Script execution engine that runs scripts inside a Podman container for sandboxed execution.
 *
 * This provides isolation from the host system while still allowing scripts to:
 * - Read input files via INPUT_DIR
 * - Write output artifacts via OUTPUT_DIR
 * - Access network (can be disabled)
 *
 * Podman is a daemonless, rootless container engine that is CLI-compatible with Docker.
 * Unlike Docker, it does not require a running daemon or root privileges, making it
 * suitable for environments where Docker Desktop is unavailable or undesired.
 *
 * All staging, I/O, timeout, and artifact logic is inherited from
 * [AbstractContainerSkillScriptExecutionEngine]; this class only supplies Podman-specific
 * details (the `podman` CLI, no `--workdir` support — a shell wrapper is used instead,
 * and the "installed and functional?" availability hint).
 *
 * @param image the OCI image to use for execution (compatible with Docker-built images)
 * @param timeout maximum execution time before killing the container
 * @param supportedLanguages which script languages this engine supports
 * @param networkEnabled whether to allow network access from the container
 * @param memoryLimit memory limit for the container (e.g., [MemorySize.megabytes] (512)), passed to Podman's `--memory`.
 *                    This is a safety guardrail: skill scripts are untrusted code, so a hard memory
 *                    cap prevents a runaway or malicious script from exhausting host RAM (OOM/DoS).
 *                    The default 512 MB is a conservative "enough for typical Python/shell scripts"
 *                    value rather than a benchmarked figure; raise it for memory-heavy workloads or
 *                    pass `null` to disable the limit entirely.
 * @param cpuLimit CPU limit for the container as a number of cores (e.g., [CpuLimit.cores] (1)), passed to Podman's `--cpus`.
 *                 Like [memoryLimit], this bounds untrusted scripts so they cannot starve the host of
 *                 CPU. The default 1 core is a safe, general-purpose default; increase it
 *                 for compute-heavy scripts or pass `null` to remove the limit.
 * @param environment additional environment variables to pass to the container
 * @param workDir working directory inside the container
 * @param user user to run as inside the container (default: "agent" for the embabel image)
 * @param fileTools FileReadTools for resolving input file paths securely.
 *                  Input paths are resolved relative to the fileTools root with path traversal protection.
 *                  Defaults to current working directory.
 */
class PodmanSkillScriptExecutionEngine @JvmOverloads constructor(
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

    override val containerCommand = "podman"
    override val containerName = "Podman"
    override val tempDirPrefix = "skills-podman-"
    override val daemonErrorMessage = "is Podman installed and functional?"

    // Podman does not auto-create the working directory if it doesn't exist in the image
    // (would cause exit 126). The base class wraps the command in /bin/sh to mkdir -p + cd
    // when this flag is false, matching Docker's implicit behavior.
    override val useWorkdir = false

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
        fun confinedTo(root: String): PodmanSkillScriptExecutionEngine =
            PodmanSkillScriptExecutionEngine(fileTools = FileTools.readWrite(root))

        /** Check if Podman is available on this system. */
        fun isPodmanAvailable(): Boolean =
            AbstractContainerSkillScriptExecutionEngine.isAvailable("podman")

        /** Check if an OCI image exists locally. */
        fun imageExists(image: String): Boolean =
            AbstractContainerSkillScriptExecutionEngine.imageExists("podman", image)

        /**
         * Ensure the default sandbox image exists, logging build instructions if not.
         *
         * @return true if the image is ready to use
         */
        fun ensureDefaultImageExists(): Boolean =
            AbstractContainerSkillScriptExecutionEngine.ensureDefaultImageExists(
                containerCommand = "podman",
                containerName = "Podman",
                buildInstructions = """
                    |OCI image '$DEFAULT_IMAGE' not found.
                    |
                    |Build it from the embabel-agent-skills module (Podman can build Dockerfiles):
                    |  podman build -t $DEFAULT_IMAGE ./embabel-agent-skills/docker
                    |
                    |Or specify a different image:
                    |  PodmanSkillScriptExecutionEngine(image = "your-image:tag")
                    """.trimMargin(),
            )

        /** Create an engine with Python-only support. */
        fun pythonOnly(
            image: String = DEFAULT_IMAGE,
            timeout: Duration = 60.seconds,
        ) = PodmanSkillScriptExecutionEngine(
            image = image,
            timeout = timeout,
            supportedLanguages = setOf(ScriptLanguage.PYTHON),
        )

        /**
         * Create an engine with maximum isolation for the least-trusted scripts: no network access
         * and tighter resource caps (256 MB memory, 0.5 CPU) than the constructor defaults. The
         * reduced limits shrink the blast radius of a hostile or buggy script at the cost of raw
         * performance; use this when running arbitrary/third-party skills.
         */
        fun isolated(
            image: String = DEFAULT_IMAGE,
            timeout: Duration = 30.seconds,
        ) = PodmanSkillScriptExecutionEngine(
            image = image,
            timeout = timeout,
            networkEnabled = false,
            memoryLimit = MemorySize.megabytes(256),
            cpuLimit = CpuLimit.millicores(500),
        )
    }
}
