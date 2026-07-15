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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ConfinedInputResolverTest {

    @TempDir
    lateinit var userRoot: Path

    private fun resolver() = ConfinedInputResolver(FileTools.readWrite(userRoot.toString()))

    @Test
    fun `resolves a file inside the user root`() {
        val file = Files.writeString(userRoot.resolve("data.txt"), "x")
        assertEquals(file, resolver().resolve(userRoot.resolve("data.txt")))
    }

    @Test
    fun `a missing file inside the user root propagates IllegalArgumentException (not found)`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            resolver().resolve(userRoot.resolve("nope.txt"))
        }
        assertTrue(e.message!!.contains("does not exist"), "message was: ${e.message}")
    }

    @Test
    fun `a file outside both roots is rejected as path traversal`(@TempDir outside: Path) {
        val secret = Files.writeString(outside.resolve("secret.txt"), "TOP_SECRET")
        assertThrows(SecurityException::class.java) {
            resolver().resolve(secret)
        }
    }

    @Test
    fun `an artifact staged from a previous run is resolvable as input (chaining)`() {
        val resolver = resolver()

        // Produce an artifact from a fake output dir, then feed its path back as input.
        val outputDir = Files.createTempDirectory("out-")
        Files.writeString(outputDir.resolve("result.pdf"), "PDF")
        val artifact = resolver.stageArtifacts(outputDir).single()

        assertTrue(artifact.path.startsWith(resolver.artifactsRoot))
        assertEquals(artifact.path, resolver.resolve(artifact.path))
    }

    @Test
    fun `stageArtifacts returns empty and creates no run dir when there is nothing to stage`() {
        val resolver = resolver()
        val emptyOutput = Files.createTempDirectory("out-empty-")

        assertTrue(resolver.stageArtifacts(emptyOutput).isEmpty())
        Files.list(resolver.artifactsRoot).use { runs ->
            assertEquals(0, runs.count(), "no run-* dir should be created for an empty output")
        }
    }
}
