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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

class ProcessSkillScriptExecutionEngineTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `supportedLanguages returns configured languages`() {
        val engine = ProcessSkillScriptExecutionEngine(
            supportedLanguages = setOf(ScriptLanguage.BASH, ScriptLanguage.PYTHON)
        )

        assertEquals(setOf(ScriptLanguage.BASH, ScriptLanguage.PYTHON), engine.supportedLanguages())
    }

    @Test
    fun `validate returns Denied for unsupported language`() {
        val engine = ProcessSkillScriptExecutionEngine(
            supportedLanguages = setOf(ScriptLanguage.BASH)
        )

        val script = createScript("test.py", ScriptLanguage.PYTHON, "print('hello')")
        val result = engine.validate(script)

        assertNotNull(result)
        assertTrue(result!!.reason.contains("not enabled"))
    }

    @Test
    fun `validate returns Denied for missing script file`() {
        val engine = ProcessSkillScriptExecutionEngine()

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
    fun `validate returns null for valid script`() {
        val engine = ProcessSkillScriptExecutionEngine()
        val script = createScript("test.sh", ScriptLanguage.BASH, "echo hello")

        val result = engine.validate(script)

        assertNull(result)
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `execute runs bash script successfully`() {
        val engine = ProcessSkillScriptExecutionEngine()
        val script = createScript("test.sh", ScriptLanguage.BASH, "#!/bin/bash\necho 'Hello World'")

        val result = engine.execute(script)

        assertTrue(result is ScriptExecutionResult.Success)
        val success = result as ScriptExecutionResult.Success
        assertEquals(0, success.exitCode)
        assertTrue(success.stdout.contains("Hello World"))
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `execute captures stderr`() {
        val engine = ProcessSkillScriptExecutionEngine()
        val script = createScript("test.sh", ScriptLanguage.BASH, "#!/bin/bash\necho 'error message' >&2")

        val result = engine.execute(script)

        assertTrue(result is ScriptExecutionResult.Success)
        val success = result as ScriptExecutionResult.Success
        assertTrue(success.stderr.contains("error message"))
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `execute captures non-zero exit code`() {
        val engine = ProcessSkillScriptExecutionEngine()
        val script = createScript("test.sh", ScriptLanguage.BASH, "#!/bin/bash\nexit 42")

        val result = engine.execute(script)

        assertTrue(result is ScriptExecutionResult.Success)
        val success = result as ScriptExecutionResult.Success
        assertEquals(42, success.exitCode)
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `execute passes arguments to script`() {
        val engine = ProcessSkillScriptExecutionEngine()
        val script = createScript("test.sh", ScriptLanguage.BASH, "#!/bin/bash\necho \"Args: \$1 \$2\"")

        val result = engine.execute(script, args = listOf("foo", "bar"))

        assertTrue(result is ScriptExecutionResult.Success)
        val success = result as ScriptExecutionResult.Success
        assertTrue(success.stdout.contains("Args: foo bar"))
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `execute provides stdin to script`() {
        val engine = ProcessSkillScriptExecutionEngine()
        val script = createScript("test.sh", ScriptLanguage.BASH, "#!/bin/bash\nread input\necho \"Got: \$input\"")

        val result = engine.execute(script, stdin = "hello from stdin")

        assertTrue(result is ScriptExecutionResult.Success)
        val success = result as ScriptExecutionResult.Success
        assertTrue(success.stdout.contains("Got: hello from stdin"))
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `execute times out long-running script`() {
        val engine = ProcessSkillScriptExecutionEngine(timeout = 500.milliseconds)
        val script = createScript("test.sh", ScriptLanguage.BASH, "#!/bin/bash\nsleep 10")

        val result = engine.execute(script)

        assertTrue(result is ScriptExecutionResult.Failure)
        val failure = result as ScriptExecutionResult.Failure
        assertTrue(failure.timedOut)
        assertTrue(failure.error.contains("timed out"))
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `execute records duration`() {
        val engine = ProcessSkillScriptExecutionEngine()
        val script = createScript("test.sh", ScriptLanguage.BASH, "#!/bin/bash\necho ok")

        val result = engine.execute(script)

        assertTrue(result is ScriptExecutionResult.Success)
        val success = result as ScriptExecutionResult.Success
        assertTrue(success.duration > 0.milliseconds)
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `execute uses custom environment variables`() {
        val engine = ProcessSkillScriptExecutionEngine(
            environment = mapOf("MY_VAR" to "my_value"),
            inheritEnvironment = true,
        )
        val script = createScript("test.sh", ScriptLanguage.BASH, "#!/bin/bash\necho \$MY_VAR")

        val result = engine.execute(script)

        assertTrue(result is ScriptExecutionResult.Success)
        val success = result as ScriptExecutionResult.Success
        assertTrue(success.stdout.contains("my_value"))
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `execute works with Python scripts`() {
        val engine = ProcessSkillScriptExecutionEngine()
        val script = createScript("test.py", ScriptLanguage.PYTHON, "print('Hello from Python')")

        val result = engine.execute(script)

        // This test will fail if python3 is not installed, which is acceptable
        if (result is ScriptExecutionResult.Failure && result.error.contains("Cannot run program")) {
            // Python not installed, skip this assertion
            return
        }

        assertTrue(result is ScriptExecutionResult.Success)
        val success = result as ScriptExecutionResult.Success
        assertEquals(0, success.exitCode)
        assertTrue(success.stdout.contains("Hello from Python"))
    }

    @Test
    fun `execute returns Denied for unsupported language`() {
        val engine = ProcessSkillScriptExecutionEngine(
            supportedLanguages = setOf(ScriptLanguage.BASH)
        )
        val script = createScript("test.py", ScriptLanguage.PYTHON, "print('hello')")

        val result = engine.execute(script)

        assertTrue(result is ScriptExecutionResult.Denied)
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `execute collects artifacts from OUTPUT_DIR`() {
        val engine = ProcessSkillScriptExecutionEngine()
        val script = createScript(
            "test.sh",
            ScriptLanguage.BASH,
            """#!/bin/bash
echo "Creating artifacts..."
echo "Hello PDF" > "${'$'}OUTPUT_DIR/result.pdf"
echo "Hello JSON" > "${'$'}OUTPUT_DIR/data.json"
echo "Done"
"""
        )

        val result = engine.execute(script)

        assertTrue(result is ScriptExecutionResult.Success)
        val success = result as ScriptExecutionResult.Success
        assertEquals(2, success.artifacts.size)

        val artifactNames = success.artifacts.map { it.name }.toSet()
        assertTrue("result.pdf" in artifactNames)
        assertTrue("data.json" in artifactNames)

        // Check mime types are inferred
        val pdfArtifact = success.artifacts.find { it.name == "result.pdf" }!!
        assertEquals("application/pdf", pdfArtifact.mimeType)

        val jsonArtifact = success.artifacts.find { it.name == "data.json" }!!
        assertEquals("application/json", jsonArtifact.mimeType)

        // Check files exist at the paths
        assertTrue(Files.exists(pdfArtifact.path))
        assertTrue(Files.exists(jsonArtifact.path))
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `execute returns empty artifacts when nothing written to OUTPUT_DIR`() {
        val engine = ProcessSkillScriptExecutionEngine()
        val script = createScript("test.sh", ScriptLanguage.BASH, "#!/bin/bash\necho 'no artifacts'")

        val result = engine.execute(script)

        assertTrue(result is ScriptExecutionResult.Success)
        val success = result as ScriptExecutionResult.Success
        assertTrue(success.artifacts.isEmpty())
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `artifacts include file sizes`() {
        val engine = ProcessSkillScriptExecutionEngine()
        val script = createScript(
            "test.sh",
            ScriptLanguage.BASH,
            """#!/bin/bash
echo "Some content here" > "${'$'}OUTPUT_DIR/output.txt"
"""
        )

        val result = engine.execute(script)

        assertTrue(result is ScriptExecutionResult.Success)
        val success = result as ScriptExecutionResult.Success
        assertEquals(1, success.artifacts.size)

        val artifact = success.artifacts[0]
        assertTrue(artifact.sizeBytes > 0)
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `execute makes input files available in INPUT_DIR`() {
        val engine = ProcessSkillScriptExecutionEngine(fileTools = FileTools.readWrite(tempDir.toString()))
        val script = createScript(
            "test.sh",
            ScriptLanguage.BASH,
            """#!/bin/bash
# List input files
ls "${'$'}INPUT_DIR"
# Copy input to output
cat "${'$'}INPUT_DIR/input.txt" > "${'$'}OUTPUT_DIR/output.txt"
"""
        )

        // Create an input file
        val inputFile = tempDir.resolve("input.txt")
        Files.writeString(inputFile, "Hello from input file")

        val result = engine.execute(script, inputFiles = listOf(inputFile))

        assertTrue(result is ScriptExecutionResult.Success)
        val success = result as ScriptExecutionResult.Success
        assertTrue(success.stdout.contains("input.txt"))
        assertEquals(1, success.artifacts.size)

        // Verify the content was copied
        val outputContent = Files.readString(success.artifacts[0].path)
        assertEquals("Hello from input file", outputContent)
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `execute handles multiple input files`() {
        val engine = ProcessSkillScriptExecutionEngine(fileTools = FileTools.readWrite(tempDir.toString()))
        val script = createScript(
            "test.sh",
            ScriptLanguage.BASH,
            """#!/bin/bash
# Count input files
ls -1 "${'$'}INPUT_DIR" | wc -l | tr -d ' '
"""
        )

        // Create multiple input files
        val inputFile1 = tempDir.resolve("file1.txt")
        val inputFile2 = tempDir.resolve("file2.txt")
        val inputFile3 = tempDir.resolve("file3.pdf")
        Files.writeString(inputFile1, "content1")
        Files.writeString(inputFile2, "content2")
        Files.writeString(inputFile3, "content3")

        val result = engine.execute(script, inputFiles = listOf(inputFile1, inputFile2, inputFile3))

        assertTrue(result is ScriptExecutionResult.Success)
        val success = result as ScriptExecutionResult.Success
        assertTrue(success.stdout.trim().contains("3"))
    }

    @Test
    fun `execute returns Denied for non-existent input file`() {
        val engine = ProcessSkillScriptExecutionEngine(fileTools = FileTools.readWrite(tempDir.toString()))
        val script = createScript("test.sh", ScriptLanguage.BASH, "#!/bin/bash\necho ok")

        val nonExistentFile = tempDir.resolve("does-not-exist.txt")
        val result = engine.execute(script, inputFiles = listOf(nonExistentFile))

        assertTrue(result is ScriptExecutionResult.Denied)
        assertTrue((result as ScriptExecutionResult.Denied).reason.contains("does not exist"))
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `execute must deny input files located outside the confinement root`() {
        // Regression test for the input-path-confinement gap (issue #1754).
        //
        // The engine should confine input files to a configured root - as
        // DockerSkillScriptExecutionEngine already does via
        // FileTools.resolveAndValidateFile(...) - and deny anything outside it.
        //
        // The input below lives under the JUnit @TempDir, i.e. under the system
        // temp dir, which is OUTSIDE the process working directory that the
        // engine's default root should be.
        //
        // EXPECTED (after fix): Denied.
        // CURRENT (buggy): the engine copies the out-of-root file into INPUT_DIR and
        // runs the script over it, returning Success - so this assertion FAILS, which
        // is exactly what confirms the vulnerability.
        val engine = ProcessSkillScriptExecutionEngine()
        val script = createScript(
            "leak.sh",
            ScriptLanguage.BASH,
            "#!/bin/bash\ncat \"${'$'}INPUT_DIR\"/*",
        )

        val outsideRoot = tempDir.resolve("secret.txt")
        Files.writeString(outsideRoot, "TOP_SECRET")

        val result = engine.execute(script, inputFiles = listOf(outsideRoot))

        assertTrue(
            result is ScriptExecutionResult.Denied,
            "Input file outside the confinement root must be denied, but got: $result",
        )
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `a text-transform skill must not deadlock and must process all of a large input`() {
        // Realistic scenario: a "transform this text" skill (read stdin -> write stdout),
        // e.g. uppercasing a large pasted document. `tr` streams (reads a block -> writes
        // a block), so read and write interleave, like any Unix filter.
        //
        // The engine used to write ALL of stdin before starting the stdout readers, so
        // once the filter's stdout pipe filled it blocked (parent not draining), stopped
        // reading stdin, and the parent blocked writing stdin -> deadlock, before
        // waitFor(timeout).
        //
        // assertTimeoutPreemptively turns a deadlock into a failure instead of hanging the
        // suite; the content assertions prove the full 1MB round-tripped (not just that
        // the call returned).
        val engine = ProcessSkillScriptExecutionEngine()
        val script = createScript("upper.sh", ScriptLanguage.BASH, "#!/bin/bash\ntr 'a-z' 'A-Z'\n")

        val bigInput = "y".repeat(1048576) // ~1MB, all lowercase

        var result: ScriptExecutionResult? = null
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(10)) {
            result = engine.execute(script, stdin = bigInput)
        }

        assertTrue(result is ScriptExecutionResult.Success, "Expected success but got: $result")
        val success = result as ScriptExecutionResult.Success
        assertEquals(bigInput.length, success.stdout.length, "The whole input must be transformed")
        assertTrue(success.stdout.all { it == 'Y' }, "Every character must be uppercased")
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `an artifact produced by one skill can be reused as input to the next skill`() {
        // A produces a file; B must be able to take it as input (chaining).
        val engine = ProcessSkillScriptExecutionEngine(fileTools = FileTools.readWrite(tempDir.toString()))

        val producer = createScript(
            "produce.sh",
            ScriptLanguage.BASH,
            "#!/bin/bash\necho \"PDF_CONTENT\" > \"${'$'}OUTPUT_DIR/result.pdf\"\n",
        )
        val produced = engine.execute(producer)
        assertTrue(produced is ScriptExecutionResult.Success, "Producer failed: $produced")
        val artifact = (produced as ScriptExecutionResult.Success).artifacts.single()

        val consumer = createScript(
            "consume.sh",
            ScriptLanguage.BASH,
            "#!/bin/bash\ncat \"${'$'}INPUT_DIR/result.pdf\"\n",
        )
        val consumed = engine.execute(consumer, inputFiles = listOf(artifact.path))

        assertTrue(consumed is ScriptExecutionResult.Success, "Consumer failed: $consumed")
        assertTrue(
            (consumed as ScriptExecutionResult.Success).stdout.contains("PDF_CONTENT"),
            "The next skill should read the artifact content, but stdout was: ${consumed.stdout}",
        )
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `a script must not inherit host environment secrets`() {
        // EMBABEL_TEST_SECRET is set in the test JVM env (surefire config), standing in
        // for a real secret like an API key. A skill script must NOT be able to read it.
        //
        // EXPECTED (after fix): the script sees nothing (only a safe allowlist is passed).
        // CURRENT (buggy): the engine inherits the full host env, so the script prints the
        // secret -> this FAILS.
        assertNotNull(
            System.getenv("EMBABEL_TEST_SECRET"),
            "EMBABEL_TEST_SECRET must be set in the test JVM env (surefire)",
        )
        val engine = ProcessSkillScriptExecutionEngine()
        val script = createScript(
            "leak.sh",
            ScriptLanguage.BASH,
            "#!/bin/bash\nprintenv EMBABEL_TEST_SECRET || true\n",
        )

        val result = engine.execute(script)

        assertTrue(result is ScriptExecutionResult.Success, "got: $result")
        assertFalse(
            (result as ScriptExecutionResult.Success).stdout.contains("s3cr3t-do-not-leak"),
            "Host secret leaked into the script: ${result.stdout}",
        )
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `an empty allowlist passes no host variables - not even PATH`() {
        // Max-lockdown posture: environmentAllowlist = emptySet() drops every host var,
        // including PATH. An absolute interpreter path lets the process still start.
        // (printenv reads the real environment, unlike echo ${'$'}PATH which bash fills
        // with a built-in default.)
        val engine = ProcessSkillScriptExecutionEngine(
            interpreters = mapOf(ScriptLanguage.BASH to listOf("/bin/bash")),
            environmentAllowlist = emptySet(),
        )
        val script = createScript(
            "env.sh",
            ScriptLanguage.BASH,
            "#!/bin/bash\n" +
                "echo \"PATH=[\$(printenv PATH)]\"\n" +
                "echo \"HOME=[\$(printenv HOME)]\"\n" +
                "printenv EMBABEL_TEST_SECRET || true\n",
        )

        val result = engine.execute(script)

        assertTrue(result is ScriptExecutionResult.Success, "got: $result")
        val stdout = (result as ScriptExecutionResult.Success).stdout
        assertTrue(stdout.contains("PATH=[]"), "PATH must be empty, stdout: $stdout")
        assertTrue(stdout.contains("HOME=[]"), "HOME must be empty, stdout: $stdout")
        assertFalse(stdout.contains("s3cr3t-do-not-leak"), "secret leaked: $stdout")
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `a custom allowlist passes exactly the listed host variables`() {
        // Custom posture: pass PATH (so the script runs) and, deliberately,
        // EMBABEL_TEST_SECRET — while HOME (which IS in the default allowlist) is dropped
        // because it is not listed here.
        val engine = ProcessSkillScriptExecutionEngine(
            environmentAllowlist = setOf("PATH", "EMBABEL_TEST_SECRET"),
        )
        val script = createScript(
            "env.sh",
            ScriptLanguage.BASH,
            "#!/bin/bash\n" +
                "echo \"SECRET=[\$(printenv EMBABEL_TEST_SECRET)]\"\n" +
                "echo \"HOME=[\$(printenv HOME)]\"\n",
        )

        val result = engine.execute(script)

        assertTrue(result is ScriptExecutionResult.Success, "got: $result")
        val stdout = (result as ScriptExecutionResult.Success).stdout
        assertTrue(stdout.contains("SECRET=[s3cr3t-do-not-leak]"), "listed var must pass, stdout: $stdout")
        assertTrue(stdout.contains("HOME=[]"), "unlisted var must be dropped, stdout: $stdout")
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `two input files with the same name from different folders are both made available`() {
        // Realistic scenario: "merge the monthly report.csv from January and February".
        // Both files are named report.csv but live in different folders — the LLM passes
        // both. They must both reach the script; today the second copy collides on the
        // same target name and the run fails with a confusing "Unexpected error".
        // EXPECTED (after fix): Success, the script sees 2 files.
        val engine = ProcessSkillScriptExecutionEngine(fileTools = FileTools.readWrite(tempDir.toString()))

        val jan = tempDir.resolve("jan").resolve("report.csv")
        val feb = tempDir.resolve("feb").resolve("report.csv")
        Files.createDirectories(jan.parent)
        Files.createDirectories(feb.parent)
        Files.writeString(jan, "month,total\njan,100\n")
        Files.writeString(feb, "month,total\nfeb,200\n")

        // A "merge reports" script: count how many input files it received.
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
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `same-name inputs are disambiguated even when the suffixed name is also taken`() {
        // Two files named report.csv AND one already named report-1.csv: the suffix loop
        // must keep incrementing (report.csv, report-1.csv, report-1-1.csv) — never
        // crash, never overwrite.
        val engine = ProcessSkillScriptExecutionEngine(fileTools = FileTools.readWrite(tempDir.toString()))

        val a = tempDir.resolve("a").resolve("report.csv")
        val b = tempDir.resolve("b").resolve("report.csv")
        val c = tempDir.resolve("c").resolve("report-1.csv")
        listOf(a, b, c).forEach {
            Files.createDirectories(it.parent)
            Files.writeString(it, "x")
        }

        val script = createScript(
            "count.sh",
            ScriptLanguage.BASH,
            "#!/bin/bash\nls -1 \"${'$'}INPUT_DIR\" | wc -l | tr -d ' '\n",
        )

        val result = engine.execute(script, inputFiles = listOf(a, b, c))

        assertTrue(result is ScriptExecutionResult.Success, "got: $result")
        assertEquals(
            "3",
            (result as ScriptExecutionResult.Success).stdout.trim(),
            "All three inputs must be present with distinct names",
        )
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

        // Make executable on Unix
        scriptFile.toFile().setExecutable(true)

        return SkillScript(
            skillName = "test-skill",
            fileName = fileName,
            language = language,
            basePath = tempDir,
        )
    }
}
