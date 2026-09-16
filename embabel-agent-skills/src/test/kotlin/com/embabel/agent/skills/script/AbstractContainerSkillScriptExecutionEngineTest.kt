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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class AbstractContainerSkillScriptExecutionEngineTest {

    @Test
    fun `podman workdir is passed as a literal positional argument`() {
        val root = Files.createTempDirectory("container-command-test-")
        val workDir = "/data/\$(id -u)/a\"b"
        val engine = TestContainerEngine(root.toString(), workDir)

        try {
            val command = engine.buildContainerCommand(
                command = listOf("python3", "/script/test.py"),
                scriptDir = root.resolve("script"),
                inputDir = root.resolve("input"),
                outputDir = root.resolve("output"),
                launcherFailureFile = root.resolve("output/.embabel-launcher-failed-test"),
                containerIdFile = root.resolve("container.cid"),
                containerInstanceName = "test-container",
            )

            assertEquals("test-container", command[command.indexOf("--name") + 1])
            assertEquals(root.resolve("container.cid").toString(), command[command.indexOf("--cidfile") + 1])
            assertFalse(command.contains("--rm"))
            assertEquals(
                "trap 'status=\$?; : > /output/.embabel-launcher-failed-test; exit \"\$status\"' 0; " +
                    "mkdir -p \"\$1\" && cd \"\$1\" && shift && exec \"\$@\"",
                command[command.indexOf("-c") + 1],
            )
            assertTrue(command.contains(workDir))
            assertFalse(command[command.indexOf("-c") + 1].contains(workDir))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `container not created is a startup failure`() {
        val engine = TestContainerEngine(System.getProperty("java.io.tmpdir"), "/work")

        assertEquals(
            AbstractContainerSkillScriptExecutionEngine.ContainerStartupStatus.FAILED,
            engine.determineContainerStartupStatus(
                exitCode = 125,
                stdout = "",
                inspection = AbstractContainerSkillScriptExecutionEngine.ContainerStateInspection.NotCreated,
            ),
        )
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `container runtime startup error returns Failure`() {
        val root = Files.createTempDirectory("container-startup-failure-test-")
        val runtime = root.resolve("fake-podman")
        val scripts = root.resolve("scripts").also { Files.createDirectories(it) }
        writeFakeRuntime(
            runtime = runtime,
            exitCode = 125,
            stderr = "runtime failed before creating the container",
        )
        Files.writeString(scripts.resolve("test.sh"), "echo should-not-run")

        try {
            val engine = TestContainerEngine(root.toString(), "/work", runtime.toString())
            val script = SkillScript("test", "test.sh", ScriptLanguage.BASH, root)

            val result = engine.execute(script)

            assertTrue(result is ScriptExecutionResult.Failure, "Expected Failure but got: $result")
            val failure = result as ScriptExecutionResult.Failure
            assertEquals(125, failure.exitCode)
            assertEquals("Podman failed to start the script", failure.error)
            assertTrue(failure.stderr!!.contains("runtime failed"))
            assertFalse(failure.timedOut)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `structured startup error returns Failure and removes container`() {
        val root = Files.createTempDirectory("container-state-error-test-")
        val runtime = root.resolve("fake-podman")
        val removedMarker = runtime.resolveSibling("${runtime.fileName}.removed")
        val scripts = root.resolve("scripts").also { Files.createDirectories(it) }
        writeFakeRuntime(
            runtime = runtime,
            exitCode = 127,
            stderr = "an unfamiliar runtime diagnostic",
            stateError = "executable file not found",
        )
        Files.writeString(scripts.resolve("test.sh"), "echo should-not-run")

        try {
            val engine = TestContainerEngine(root.toString(), "/work", runtime.toString())
            val script = SkillScript("test", "test.sh", ScriptLanguage.BASH, root)

            val result = engine.execute(script)

            assertTrue(result is ScriptExecutionResult.Failure, "Expected Failure but got: $result")
            val failure = result as ScriptExecutionResult.Failure
            assertEquals(127, failure.exitCode)
            assertEquals("Podman failed to start the script", failure.error)
            assertTrue(failure.stderr!!.contains("unfamiliar runtime diagnostic"))
            assertTrue(Files.exists(removedMarker), "Expected the stopped container to be removed")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `empty structured startup error preserves reserved script exits and removes containers`() {
        for (exitCode in 125..127) {
            val root = Files.createTempDirectory("container-state-empty-test-")
            val runtime = root.resolve("fake-podman")
            val removedMarker = runtime.resolveSibling("${runtime.fileName}.removed")
            val scripts = root.resolve("scripts").also { Files.createDirectories(it) }
            writeFakeRuntime(
                runtime = runtime,
                exitCode = exitCode,
                stderr = "Error: script chose exit $exitCode",
                stateError = "",
            )
            Files.writeString(scripts.resolve("test.sh"), "exit $exitCode")

            try {
                val engine = TestContainerEngine(root.toString(), "/work", runtime.toString())
                val script = SkillScript("test", "test.sh", ScriptLanguage.BASH, root)

                val result = engine.execute(script)

                assertTrue(result is ScriptExecutionResult.Success, "Expected Success but got: $result")
                val success = result as ScriptExecutionResult.Success
                assertEquals(exitCode, success.exitCode)
                assertTrue(success.stderr.contains("Error: script chose exit $exitCode"))
                assertTrue(Files.exists(removedMarker), "Expected the stopped container to be removed")
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `unavailable container inspection returns Failure and removes container`() {
        val root = Files.createTempDirectory("container-state-unavailable-test-")
        val runtime = root.resolve("fake-podman")
        val removedMarker = runtime.resolveSibling("${runtime.fileName}.removed")
        val scripts = root.resolve("scripts").also { Files.createDirectories(it) }
        writeFakeRuntime(
            runtime = runtime,
            exitCode = 125,
            stderr = "runtime state unavailable",
            stateError = "",
            inspectionExitCode = 1,
        )
        Files.writeString(scripts.resolve("test.sh"), "exit 125")

        try {
            val engine = TestContainerEngine(root.toString(), "/work", runtime.toString())
            val script = SkillScript("test", "test.sh", ScriptLanguage.BASH, root)

            val result = engine.execute(script)

            assertTrue(result is ScriptExecutionResult.Failure, "Expected Failure but got: $result")
            val failure = result as ScriptExecutionResult.Failure
            assertEquals(125, failure.exitCode)
            assertEquals("Podman container state inspection failed", failure.error)
            assertTrue(Files.exists(removedMarker), "Expected the stopped container to be removed")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `structured state error is a container startup failure`() {
        val engine = TestContainerEngine(System.getProperty("java.io.tmpdir"), "/work")

        assertEquals(
            AbstractContainerSkillScriptExecutionEngine.ContainerStartupStatus.FAILED,
            engine.determineContainerStartupStatus(
                exitCode = 127,
                stdout = "",
                inspection = AbstractContainerSkillScriptExecutionEngine.ContainerStateInspection.Inspected(true),
            ),
        )
    }

    @Test
    fun `script non-zero exits remain successful executions`() {
        val engine = TestContainerEngine(System.getProperty("java.io.tmpdir"), "/work")

        for (exitCode in 125..127) {
            assertEquals(
                AbstractContainerSkillScriptExecutionEngine.ContainerStartupStatus.STARTED,
                engine.determineContainerStartupStatus(
                    exitCode = exitCode,
                    stdout = "",
                    inspection = AbstractContainerSkillScriptExecutionEngine.ContainerStateInspection.Inspected(false),
                ),
                "Exit code $exitCode without a runtime diagnostic must remain a script result",
            )
        }
    }

    @Test
    fun `unavailable inspection produces unknown startup status`() {
        val engine = TestContainerEngine(System.getProperty("java.io.tmpdir"), "/work")

        assertEquals(
            AbstractContainerSkillScriptExecutionEngine.ContainerStartupStatus.UNKNOWN,
            engine.determineContainerStartupStatus(
                exitCode = 125,
                stdout = "",
                inspection = AbstractContainerSkillScriptExecutionEngine.ContainerStateInspection.Unavailable,
            ),
        )
    }

    @Test
    fun `runtime check timeout destroys process and returns false`() {
        val process = mockk<Process>()
        every { process.waitFor(5, TimeUnit.SECONDS) } returns false
        every { process.destroyForcibly() } returns process

        val result = AbstractContainerSkillScriptExecutionEngine.processCompletesSuccessfully(process)

        assertFalse(result)
        verify(exactly = 1) { process.destroyForcibly() }
    }

    @Test
    fun `interrupted runtime check destroys process and restores interrupt`() {
        val process = mockk<Process>()
        every { process.waitFor(5, TimeUnit.SECONDS) } throws InterruptedException("test interruption")
        every { process.destroyForcibly() } returns process

        try {
            val result = AbstractContainerSkillScriptExecutionEngine.processCompletesSuccessfully(process)

            assertFalse(result)
            assertTrue(Thread.currentThread().isInterrupted)
            verify(exactly = 1) { process.destroyForcibly() }
        } finally {
            Thread.interrupted()
        }
    }

    private fun writeFakeRuntime(
        runtime: java.nio.file.Path,
        exitCode: Int,
        stderr: String,
        stateError: String? = null,
        inspectionExitCode: Int = 0,
    ) {
        Files.writeString(
            runtime,
            """
                |#!/bin/sh
                |case "${'$'}1" in
                |  version)
                |    exit 0
                |    ;;
                |  run)
                |    shift
                |    while [ "${'$'}#" -gt 0 ]; do
                |      if [ "${'$'}1" = "--cidfile" ]; then
                |        ${if (stateError == null) ":" else "printf '%s\\n' fake-container-id > \"${'$'}2\""}
                |        break
                |      fi
                |      shift
                |    done
                |    printf '%s\n' '$stderr' >&2
                |    exit $exitCode
                |    ;;
                |  container)
                |    if [ "${'$'}2" = "inspect" ]; then
                |      printf '%s\n' '${stateError.orEmpty()}'
                |      exit $inspectionExitCode
                |    fi
                |    ;;
                |  rm)
                |    touch "${'$'}0.removed"
                |    exit 0
                |    ;;
                |esac
                |exit 2
            """.trimMargin(),
        )
        assertTrue(runtime.toFile().setExecutable(true))
    }

    @Test
    fun `buildContainerCommand includes --cpus when effectiveCpuLimit returns a value`() {
        val root = Files.createTempDirectory("container-command-test-cpu-")
        val engine = TestContainerEngine(root.toString(), "/work", cpuLimit = CpuLimit.cores(2))
        try {
            val command = engine.buildContainerCommand(
                command = listOf("bash", "/script/test.sh"),
                scriptDir = root.resolve("script"),
                inputDir = root.resolve("input"),
                outputDir = root.resolve("output"),
                launcherFailureFile = root.resolve("output/.embabel-launcher-failed-test"),
                containerIdFile = root.resolve("container.cid"),
                containerInstanceName = "test-container",
            )
            val cpusIdx = command.indexOf("--cpus")
            assertTrue(cpusIdx >= 0, "--cpus must be present when effectiveCpuLimit() returns a value")
            assertEquals("2", command[cpusIdx + 1])
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `buildContainerCommand omits --cpus when effectiveCpuLimit returns null`() {
        val root = Files.createTempDirectory("container-command-test-cpu-null-")
        // cpuLimit set but overridden to return null via subclass
        val engine = object : TestContainerEngine(root.toString(), "/work", cpuLimit = CpuLimit.cores(1)) {
            override fun effectiveCpuLimit(): CpuLimit? = null
        }
        try {
            val command = engine.buildContainerCommand(
                command = listOf("bash", "/script/test.sh"),
                scriptDir = root.resolve("script"),
                inputDir = root.resolve("input"),
                outputDir = root.resolve("output"),
                launcherFailureFile = root.resolve("output/.embabel-launcher-failed-test"),
                containerIdFile = root.resolve("container.cid"),
                containerInstanceName = "test-container",
            )
            assertFalse(command.contains("--cpus"), "--cpus must be absent when effectiveCpuLimit() returns null")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private open class TestContainerEngine(
        root: String,
        workDir: String,
        override val containerCommand: String = "podman",
        cpuLimit: CpuLimit? = null,
    ) : AbstractContainerSkillScriptExecutionEngine(
        image = "test-image",
        timeout = 1.seconds,
        supportedLanguages = ScriptLanguage.entries.toSet(),
        networkEnabled = false,
        memoryLimit = null,
        cpuLimit = cpuLimit,
        environment = emptyMap(),
        workDir = workDir,
        user = null,
        fileTools = FileTools.readWrite(root),
    ) {
        override val containerName = "Podman"
        override val tempDirPrefix = "test-container-"
        override val daemonErrorMessage = "unavailable"
        override val useWorkdir = false
    }
}
