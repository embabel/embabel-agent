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
package com.embabel.agent.sandbox.docker

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.*

class DockerArtifactExporterTest {
    @TempDir lateinit var root: Path
    private var contents = archive("report.csv" to "one")
    private var failure: Exception? = null

    private fun exporter(config: ArtifactExportConfig = ArtifactExportConfig(root)) =
        DockerArtifactExporter(config) { _, _, target, _ ->
            Files.write(target, contents)
            failure?.let { throw it }
        }

    private fun publish(config: ArtifactExportConfig = ArtifactExportConfig(root), name: String = "report.csv") =
        exporter(config).export("session", "/tmp/$name", ArtifactDeadline(config.timeout))

    @Test
    fun `each request publishes an independent readable file even when contents are unchanged`() {
        val first = publish()
        val second = publish()
        assertNotEquals(first.path, second.path)
        assertEquals("report.csv", first.name)
        assertEquals("text/csv", first.mimeType)
        assertEquals(3L, first.sizeBytes)
        contents = archive("report.csv" to "two")
        val changed = publish()
        assertEquals("one", Files.readString(first.path))
        assertEquals("one", Files.readString(second.path))
        assertEquals("two", Files.readString(changed.path))
        assertNoStaging()
    }

    @Test
    fun `failed transfer leaves previous exports readable and cleans partial files`() {
        val first = publish()
        failure = IOException("copy failed")
        assertContains(assertFailsWith<IOException> { publish() }.message!!, "copy failed")
        assertEquals("one", Files.readString(first.path))
        assertNoStaging()
        failure = null
        assertEquals("one", Files.readString(publish().path))
    }

    @Test
    fun `rejects extra entries and unexpected paths without publishing partial results`() {
        for (name in listOf("../escape", "a/../../escape", "C:secret", "bad\nname", "other.csv", "nested/report.csv")) {
            contents = archive(name to "bad")
            assertFailsWith<IllegalArgumentException>(name) { publish() }
            assertEmptyRoot()
        }
        contents = archive("report.csv" to "ok", "report.csv" to "duplicate")
        assertFailsWith<IllegalArgumentException> { publish() }
        assertEmptyRoot()
        // Test a literal Windows separator before the archive library normalizes it.
        contents = archive("a_b" to "bad")
        contents[1] = '\\'.code.toByte()
        for (i in 148..155) contents[i] = ' '.code.toByte()
        val checksum = contents.copyOfRange(0, 512).sumOf { it.toInt() and 255 }
        checksum.toString(8).padStart(6, '0').toByteArray().copyInto(contents, 148)
        contents[154] = 0
        assertFailsWith<IllegalArgumentException> { publish() }
        assertEmptyRoot()
    }

    @Test
    fun `rejects directories links special files missing entries and malformed archives`() {
        for (type in listOf(TarConstants.LF_SYMLINK, TarConstants.LF_LINK, TarConstants.LF_FIFO,
            TarConstants.LF_CHR, TarConstants.LF_BLK, TarConstants.LF_DIR)) {
            val bytes = ByteArrayOutputStream()
            TarArchiveOutputStream(bytes).use { tar ->
                tar.putArchiveEntry(TarArchiveEntry("report.csv", type).apply { linkName = "/etc/passwd" })
                tar.closeArchiveEntry()
            }
            contents = bytes.toByteArray()
            assertFailsWith<IllegalArgumentException> { publish() }
            assertEmptyRoot()
        }
        contents = archive()
        assertFailsWith<IllegalArgumentException> { publish() }
        contents = archive("report.csv" to "ok").copyOf(1024)
        assertFails { publish() }
        contents = archive("report.csv" to "ok").also { it[0] = 'X'.code.toByte() }
        assertFailsWith<IllegalArgumentException> { publish() }
        assertEmptyRoot()
    }

    @Test
    fun `supports long names and empty files`() {
        val name = "a".repeat(150) + ".txt"
        contents = archive(name to "")
        val file = publish(name = name)
        assertEquals(name, file.name)
        assertEquals(0L, file.sizeBytes)
        assertEquals("", Files.readString(file.path))
    }

    @Test
    fun `enforces payload archive and metadata entry limits`() {
        contents = archive("report.csv" to "123")
        assertContains(assertFailsWith<IllegalArgumentException> {
            publish(ArtifactExportConfig(root, maxFileBytes = 2))
        }.message!!, "maxFileBytes")
        assertFailsWith<IllegalArgumentException> {
            publish(ArtifactExportConfig(root, maxFileBytes = 3, maxArchiveBytes = 1024))
        }
        val name = "a".repeat(150) + ".txt"
        contents = archive(name to "ok")
        assertFailsWith<IllegalArgumentException> { publish(ArtifactExportConfig(root, maxEntries = 1), name) }
        assertEmptyRoot()
    }

    @Test
    fun `remaining payload allowance is enforced independently of the per-file config`() {
        val config = ArtifactExportConfig(root)
        assertFailsWith<ArtifactPayloadLimitException> {
            exporter(config).export("session", "/tmp/report.csv", ArtifactDeadline(config.timeout), 2)
        }
        assertEmptyRoot()
        contents = archive("report.csv" to "")
        val file = exporter(config).export("session", "/tmp/report.csv", ArtifactDeadline(config.timeout), 0)
        assertEquals(0L, file.sizeBytes)
        assertEquals("", Files.readString(file.path))
    }

    @Test
    fun `transfer and archive processing share the caller deadline`() {
        val deadline = ArtifactDeadline(Duration.ofMillis(20))
        val exporter = DockerArtifactExporter(ArtifactExportConfig(root)) { _, _, target, sameDeadline ->
            assertSame(deadline, sameDeadline)
            Files.write(target, contents)
            Thread.sleep(40)
        }
        assertContains(assertFailsWith<IOException> {
            exporter.export("session", "/tmp/report.csv", deadline)
        }.message!!, "timed out")
        assertEmptyRoot()
    }

    @Test
    fun `interruption propagates with flag restored and staging removed`() {
        failure = InterruptedException("cancelled")
        try {
            assertFailsWith<InterruptedException> { publish() }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
        assertEmptyRoot()
    }

    @Test
    fun `validates paths and config before transfer`() {
        for (path in listOf("/", "relative", "/a/../b", "/a/", "/a//b", "/a\\b", "/a\nb", "/a\"b")) {
            assertFailsWith<IllegalArgumentException> { publishedFileName(path) }
        }
        assertFailsWith<IllegalArgumentException> { ArtifactExportConfig(root, maxFileBytes = 0) }
        assertFailsWith<IllegalArgumentException> { ArtifactExportConfig(root, maxEntries = 0) }
        assertFailsWith<IllegalArgumentException> { ArtifactExportConfig(root, timeout = Duration.ZERO) }
    }

    private fun assertEmptyRoot() = assertEquals(0L, Files.list(root).use { it.count() })
    private fun assertNoStaging() = assertFalse(Files.list(root).use { paths ->
        paths.anyMatch { it.fileName.toString().startsWith(".artifact-staging-") }
    })
    private fun archive(vararg files: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(bytes).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for ((name, content) in files) {
                val data = content.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong() })
                tar.write(data)
                tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }
}
