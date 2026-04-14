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
