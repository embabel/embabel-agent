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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.EnabledIf
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for DockerExecutionEngine.
 *
 * These tests require Docker to be installed and running.
 * They use the standard ubuntu:22.04 image which should be widely available.
 */
@DisabledOnOs(OS.WINDOWS)
@EnabledIf("shouldRunDockerTests", disabledReason = "Docker tests run in CI or macOS only - skipped on local Linux due to environment differences")
class DockerSkillScriptExecutionEngineTest {

    @TempDir
    lateinit var tempDir: Path

    companion object {
        // Use ubuntu image for tests since it's widely available
        private const val TEST_IMAGE = "ubuntu:22.04"

        @JvmStatic
        fun isDockerAvailable(): Boolean {
            return try {
                val process = ProcessBuilder("docker", "version")
                    .redirectErrorStream(true)
                    .start()
                process.waitFor() == 0
            } catch (e: Exception) {
                false
            }
        }

        @JvmStatic
        fun shouldRunDockerTests(): Boolean {
            // Run in CI or on macOS only - skip on local Linux due to environment differences
            val isCI = System.getenv("GITHUB_ACTIONS") != null
            val isMac = System.getProperty("os.name").lowercase().contains("mac")
            return isCI || isMac
        }
    }

    @Test
    fun `supportedLanguages returns configured languages`() {
        val engine = DockerSkillScriptExecutionEngine(
            supportedLanguages = setOf(ScriptLanguage.BASH, ScriptLanguage.PYTHON)
        )

        assertEquals(setOf(ScriptLanguage.BASH, ScriptLanguage.PYTHON), engine.supportedLanguages())
    }

    @Test
    fun `validate returns Denied for unsupported language`() {
        val engine = DockerSkillScriptExecutionEngine(
            supportedLanguages = setOf(ScriptLanguage.BASH)
        )

        val script = createScript("test.py", ScriptLanguage.PYTHON, "print('hello')")
        val result = engine.validate(script)

        assertNotNull(result)
        assertTrue(result!!.reason.contains("not enabled"))
    }

    @Test
    fun `validate returns Denied for missing script file`() {
        val engine = DockerSkillScriptExecutionEngine()

        val script = SkillScript(
            skillName = "test",
            fileName = "nonexistent.sh",
            language = ScriptLanguage.BASH,
            basePath = tempDir,
        )

        val result = engine.validate(script)

        assertNotNull(result)
        assertTrue(result!!.reason.contains("does not exist"))
    }

    @Test
    @EnabledIf("isDockerAvailable")
    fun `validate returns null when Docker is available`() {
        val engine = DockerSkillScriptExecutionEngine()
        val script = createScript("test.sh", ScriptLanguage.BASH, "echo hello")

        val result = engine.validate(script)

        assertNull(result)
    }

    @Test
    @EnabledIf("isDockerAvailable")
    fun `execute runs bash script in container`() {
        val engine = DockerSkillScriptExecutionEngine(
            image = TEST_IMAGE,
            user = null,  // ubuntu image doesn't have 'agent' user
        )
        val script = createScript("test.sh", ScriptLanguage.BASH, "#!/bin/bash\necho 'Hello from Docker'")

        val result = engine.execute(script)

        assertTrue(result is ScriptExecutionResult.Success, "Expected Success but got: $result")
        val success = result as ScriptExecutionResult.Success
        assertEquals(0, success.exitCode)
        assertTrue(success.stdout.contains("Hello from Docker"))
    }

    @Test
    @EnabledIf("isDockerAvailable")
    fun `execute captures stderr`() {
        val engine = DockerSkillScriptExecutionEngine(
            image = TEST_IMAGE,
            user = null,
        )
        val script = createScript("test.sh", ScriptLanguage.BASH, "#!/bin/bash\necho 'error' >&2")

        val result = engine.execute(script)

        assertTrue(result is ScriptExecutionResult.Success)
        val success = result as ScriptExecutionResult.Success
        assertTrue(success.stderr.contains("error"))
    }

    @Test
    @EnabledIf("isDockerAvailable")
    fun `execute captures non-zero exit code`() {
        val engine = DockerSkillScriptExecutionEngine(
            image = TEST_IMAGE,
            user = null,
        )
        val script = createScript("test.sh", ScriptLanguage.BASH, "#!/bin/bash\nexit 42")

        val result = engine.execute(script)

        assertTrue(result is ScriptExecutionResult.Success)
        val success = result as ScriptExecutionResult.Success
        assertEquals(42, success.exitCode)
    }

    @Test
    @EnabledIf("isDockerAvailable")
    fun `execute passes arguments to script`() {
        val engine = DockerSkillScriptExecutionEngine(
            image = TEST_IMAGE,
            user = null,
        )
        val script = createScript("test.sh", ScriptLanguage.BASH, "#!/bin/bash\necho \"Args: \$1 \$2\"")

        val result = engine.execute(script, args = listOf("foo", "bar"))

        assertTrue(result is ScriptExecutionResult.Success)
        val success = result as ScriptExecutionResult.Success
        assertTrue(success.stdout.contains("Args: foo bar"))
    }

    @Test
    @EnabledIf("isDockerAvailable")
    fun `execute times out long-running script`() {
        val engine = DockerSkillScriptExecutionEngine(
            image = TEST_IMAGE,
            timeout = 2.seconds,
            user = null,
        )
        val script = createScript("test.sh", ScriptLanguage.BASH, "#!/bin/bash\nsleep 30")

        val result = engine.execute(script)

        assertTrue(result is ScriptExecutionResult.Failure)
        val failure = result as ScriptExecutionResult.Failure
        assertTrue(failure.timedOut)
        assertTrue(failure.error.contains("timed out"))
    }

    @Test
    @EnabledIf("isDockerAvailable")
    fun `execute collects artifacts from OUTPUT_DIR`() {
        val engine = DockerSkillScriptExecutionEngine(
            image = TEST_IMAGE,
            user = null,
        )
        val script = createScript(
            "test.sh",
            ScriptLanguage.BASH,
            """#!/bin/bash
echo "Creating artifact..."
echo "Hello PDF" > "${'$'}OUTPUT_DIR/result.pdf"
echo "Done"
"""
        )

        val result = engine.execute(script)

        assertTrue(result is ScriptExecutionResult.Success, "Expected success but got: $result")
        val success = result as ScriptExecutionResult.Success
        assertEquals(1, success.artifacts.size)

        val artifact = success.artifacts[0]
        assertEquals("result.pdf", artifact.name)
        assertEquals("application/pdf", artifact.mimeType)
        assertTrue(Files.exists(artifact.path))
    }

    @Test
    @EnabledIf("isDockerAvailable")
    fun `an artifact produced by one skill must be usable as input to the next skill`() {
        // Chaining scenario: skill A produces a file (e.g. a PDF), skill B takes it as
        // input (e.g. to email it). The engine must keep A's artifact reachable by its
        // confined file access so B can consume it. Guards against artifacts landing in
        // a temp dir outside any trusted root.
        val engine = DockerSkillScriptExecutionEngine(
            image = TEST_IMAGE,
            user = null,
            fileTools = FileTools.readWrite(tempDir.toString()),
        )

        // Skill A: produce an artifact.
        val producer = createScript(
            "produce.sh",
            ScriptLanguage.BASH,
            "#!/bin/bash\necho \"PDF_CONTENT\" > \"${'$'}OUTPUT_DIR/result.pdf\"\n",
        )
        val produced = engine.execute(producer)
        assertTrue(produced is ScriptExecutionResult.Success, "Producer failed: $produced")
        val artifact = (produced as ScriptExecutionResult.Success).artifacts.single()

        // Skill B: consume A's artifact as an input file and echo its content.
        val consumer = createScript(
            "consume.sh",
            ScriptLanguage.BASH,
            "#!/bin/bash\ncat \"${'$'}INPUT_DIR/result.pdf\"\n",
        )
        val consumed = engine.execute(consumer, inputFiles = listOf(artifact.path))

        assertTrue(
            consumed is ScriptExecutionResult.Success,
            "The next skill must be able to consume the produced artifact, but got: $consumed",
        )
        assertTrue(
            (consumed as ScriptExecutionResult.Success).stdout.contains("PDF_CONTENT"),
            "The next skill should read the artifact content, but stdout was: ${consumed.stdout}",
        )
    }

    @Test
    @EnabledIf("isDockerAvailable")
    fun `a container must not inherit host environment secrets`() {
        // EMBABEL_TEST_SECRET is set in the test JVM env (surefire config), standing in for
        // a real secret like an API key. The container must not receive host env vars.
        // Docker isolates the container env, so this passes.
        val engine = DockerSkillScriptExecutionEngine(image = TEST_IMAGE, user = null)
        val script = createScript(
            "leak.sh",
            ScriptLanguage.BASH,
            "#!/bin/bash\nprintenv EMBABEL_TEST_SECRET || true\n",
        )

        val result = engine.execute(script)

        assertTrue(result is ScriptExecutionResult.Success, "got: $result")
        assertFalse(
            (result as ScriptExecutionResult.Success).stdout.contains("s3cr3t-do-not-leak"),
            "Host secret leaked into the container: ${result.stdout}",
        )
    }

    @Test
    @EnabledIf("isDockerAvailable")
    fun `two input files with the same name from different folders are both made available`() {
        // Realistic scenario: "merge the monthly report.csv from January and February".
        // Both files are named report.csv but live in different folders; the LLM passes
        // both. They must both reach the script; today the second copy collides on the
        // same target name and the run fails with a confusing "Unexpected error".
        val engine = DockerSkillScriptExecutionEngine(
            image = TEST_IMAGE,
            user = null,
            fileTools = FileTools.readWrite(tempDir.toString()),
        )

        val jan = tempDir.resolve("jan").resolve("report.csv")
        val feb = tempDir.resolve("feb").resolve("report.csv")
        Files.createDirectories(jan.parent)
        Files.createDirectories(feb.parent)
        Files.writeString(jan, "month,total\njan,100\n")
        Files.writeString(feb, "month,total\nfeb,200\n")

        val script = createScript(
            "merge.sh",
            ScriptLanguage.BASH,
            "#!/bin/bash\nls -1 \"${'$'}INPUT_DIR\" | wc -l | tr -d ' '\n",
        )

        val result = engine.execute(script, inputFiles = listOf(jan, feb))

        assertTrue(result is ScriptExecutionResult.Success, "Run failed on duplicate input names: $result")
        assertEquals(
            "2",
            (result as ScriptExecutionResult.Success).stdout.trim(),
            "Both report.csv files must reach the script",
        )
    }

    @Test
    @EnabledIf("isDockerAvailable")
    fun `execute makes input files available in INPUT_DIR`() {
        val engine = DockerSkillScriptExecutionEngine(
            image = TEST_IMAGE,
            user = null,
            fileTools = FileTools.readWrite(tempDir.toString()),
        )
        val script = createScript(
            "test.sh",
            ScriptLanguage.BASH,
            """#!/bin/bash
cat "${'$'}INPUT_DIR/data.txt"
"""
        )

        // Create an input file
        val inputFile = tempDir.resolve("data.txt")
        Files.writeString(inputFile, "Hello from input")

        // Pass relative path - fileTools will resolve it against tempDir
        val result = engine.execute(script, inputFiles = listOf(Path.of("data.txt")))

        assertTrue(result is ScriptExecutionResult.Success)
        val success = result as ScriptExecutionResult.Success
        assertTrue(success.stdout.contains("Hello from input"))
    }

    @Test
    fun `execute returns Denied for non-existent input file`() {
        val engine = DockerSkillScriptExecutionEngine(
            fileTools = FileTools.readWrite(tempDir.toString()),
        )
        val script = createScript("test.sh", ScriptLanguage.BASH, "echo ok")

        // Pass relative path - fileTools will resolve it against tempDir
        val result = engine.execute(script, inputFiles = listOf(Path.of("does-not-exist.txt")))

        assertTrue(result is ScriptExecutionResult.Denied)
        assertTrue((result as ScriptExecutionResult.Denied).reason.contains("does not exist"))
    }

    @Test
    fun `pythonOnly factory creates Python-only engine`() {
        val engine = DockerSkillScriptExecutionEngine.pythonOnly()

        assertEquals(setOf(ScriptLanguage.PYTHON), engine.supportedLanguages())
    }

    @Test
    fun `isolated factory creates isolated engine`() {
        val engine = DockerSkillScriptExecutionEngine.isolated()

        // Just verify it's created - detailed behavior is tested in integration tests
        assertNotNull(engine)
    }

    private fun createScript(
        fileName: String,
        language: ScriptLanguage,
        content: String,
    ): SkillScript {
        val scriptsDir = tempDir.resolve("scripts")
        Files.createDirectories(scriptsDir)

        val scriptFile = scriptsDir.resolve(fileName)
        Files.writeString(scriptFile, content)

        return SkillScript(
            skillName = "test-skill",
            fileName = fileName,
            language = language,
            basePath = tempDir,
        )
    }
}
