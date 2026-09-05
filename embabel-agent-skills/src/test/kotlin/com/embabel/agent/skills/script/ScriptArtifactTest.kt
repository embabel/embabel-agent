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

import com.embabel.chat.MaterializableAsset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ScriptArtifactTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `script artifact exposes content for durable storage`() {
        val path = tempDir.resolve("report.txt")
        Files.writeString(path, "report content")
        val artifact = ScriptArtifact(
            name = "report.txt",
            path = path,
            mimeType = "text/plain",
            sizeBytes = Files.size(path),
        )

        assertThat(artifact).isInstanceOf(MaterializableAsset::class.java)
        assertThat(artifact.persistent()).isFalse()
        artifact.openStream().bufferedReader().use { reader ->
            assertThat(reader.readText()).isEqualTo("report content")
        }
    }

    @Test
    fun `script artifact can be tracked as an asset`() {
        val path = tempDir.resolve("report.pdf")
        Files.write(path, byteArrayOf(1, 2, 3))
        val artifact = ScriptArtifact(
            name = "report.pdf",
            path = path,
            mimeType = "application/pdf",
            sizeBytes = Files.size(path),
        )

        assertThat(artifact.id).isNotBlank()
        assertThat(artifact.reference().name).isEqualTo("report.pdf")
        assertThat(artifact.reference().notes()).contains("application/pdf", path.toString())
    }
}
