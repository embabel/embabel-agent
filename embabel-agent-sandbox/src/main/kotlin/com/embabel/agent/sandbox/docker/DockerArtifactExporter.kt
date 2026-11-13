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

import com.embabel.agent.sandbox.ExecutionArtifact
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/** Validates and exports exactly one selected file. No container writes or change tracking. */
internal class DockerArtifactExporter(
    private val config: ArtifactExportConfig,
    private val copyArchive: (String, String, Path, ArtifactDeadline) -> Unit = { cid, source, target, deadline ->
        Files.newOutputStream(target).use { output ->
            // Never follow a selected symlink with -L; archive validation rejects link entries.
            ArtifactTransfer.run(listOf("docker", "cp", "$cid:$source", "-"),
                output, config.maxArchiveBytes, deadline)
        }
    },
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun export(
        containerId: String, containerPath: String, deadline: ArtifactDeadline,
        maxPayloadBytes: Long = config.maxFileBytes,
    ): ExecutionArtifact.File {
        require(maxPayloadBytes in 0..config.maxFileBytes) { "Invalid remaining payload allowance" }
        val name = publishedFileName(containerPath)
        var staging: Path? = null
        try {
            deadline.remainingNanos()
            Files.createDirectories(config.hostRoot)
            val root = config.hostRoot.toRealPath()
            val attributes = if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
                arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            } else emptyArray()
            staging = Files.createTempDirectory(root, ".artifact-staging-", *attributes)
            val archive = staging.resolve("selected.tar")
            copyArchive(containerId, containerPath, archive, deadline)
            validateArchive(archive, deadline)
            val file = readArchive(archive, staging, name, deadline, maxPayloadBytes)
            Files.delete(archive)
            deadline.remainingNanos()
            val published = root.resolve("artifacts-${UUID.randomUUID()}")
            Files.move(staging, published)
            staging = null
            return file.copy(path = published.resolve(file.path.fileName))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } finally {
            staging?.let { removeStaging(it) }
        }
    }

    // Preflight physical headers before Commons Compress interprets extension records.
    // This bounds metadata allocation, extension recursion, and otherwise hidden entries.
    private fun validateArchive(archive: Path, deadline: ArtifactDeadline) {
        val size = Files.size(archive)
        require(size in 1024..config.maxArchiveBytes && size % 512 == 0L) { "Invalid or oversized artifact archive" }
        RandomAccessFile(archive.toFile(), "r").use { input ->
            var entries = 0
            var extensions = 0
            val header = ByteArray(512)
            while (input.filePointer < size) {
                deadline.remainingNanos()
                input.readFully(header)
                if (header.all { it == 0.toByte() }) {
                    require(size - input.filePointer >= 512) { "Incomplete archive terminator" }
                    while (input.filePointer < size) {
                        deadline.remainingNanos()
                        input.readFully(header)
                        require(header.all { it == 0.toByte() }) { "Data after archive terminator" }
                    }
                    return
                }
                require(++entries <= config.maxEntries) { "Artifact entry limit exceeded" }
                val entry = TarArchiveEntry(header)
                require(entry.isCheckSumOK) { "Invalid archive header checksum" }
                val type = header[156].toInt().toChar()
                require(type in listOf('\u0000', '0', 'x', 'L')) { "Unsupported artifact entry type" }
                if (type == 'x' || type == 'L') {
                    require(++extensions <= 8 && entry.size <= 16 * 1024) { "Artifact metadata limit exceeded" }
                } else {
                    extensions = 0
                    // Validate before the archive library normalizes platform-specific names.
                    val rawName = header.copyOfRange(0, 100).takeWhile { it != 0.toByte() }
                        .toByteArray().toString(Charsets.UTF_8)
                    require(rawName.none { it == '\\' || it == ':' || it.isISOControl() }) { "Unsafe artifact path" }
                    require(!rawName.startsWith('/')) { "Unsafe artifact path" }
                }
                require(entry.size >= 0 && entry.size <= size - input.filePointer) { "Truncated artifact archive" }
                val padded = ((entry.size + 511) / 512) * 512
                require(padded <= size - input.filePointer) { "Truncated artifact archive" }
                input.seek(input.filePointer + padded)
            }
        }
        throw IOException("Missing archive terminator")
    }

    private fun readArchive(
        archive: Path, staging: Path, name: String, deadline: ArtifactDeadline, maxPayloadBytes: Long,
    ): ExecutionArtifact.File {
        var file: ExecutionArtifact.File? = null
        TarArchiveInputStream(Files.newInputStream(archive)).use { tar ->
            while (true) {
                deadline.remainingNanos()
                val entry = tar.nextEntry ?: break
                require(file == null) { "Publication must contain exactly one file" }
                require(entry.isFile && !entry.isSymbolicLink && !entry.isLink && !entry.isSparse) {
                    "Only a regular file can be published"
                }
                require(entry.isCheckSumOK && tar.canReadEntryData(entry)) { "Unsupported archive entry" }
                require(entry.name == name) { "Archive contains a path other than the selected file" }
                require(entry.size >= 0) { "Invalid artifact size" }
                if (entry.size > maxPayloadBytes) throw ArtifactPayloadLimitException(maxPayloadBytes)
                val target = staging.resolve(UUID.randomUUID().toString())
                var written = 0L
                Files.newOutputStream(target).use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        deadline.remainingNanos()
                        val read = tar.read(buffer)
                        if (read < 0) break
                        if (read.toLong() > maxPayloadBytes - written) throw ArtifactPayloadLimitException(maxPayloadBytes)
                        written += read
                        output.write(buffer, 0, read)
                    }
                }
                require(written == entry.size) { "Incomplete artifact file" }
                file = ExecutionArtifact.File(name, target, ExecutionArtifact.inferMimeType(name), written)
            }
        }
        return requireNotNull(file) { "Archive did not contain the selected file" }
    }

    private fun removeStaging(directory: Path) {
        try {
            // Staging contains only generated flat file names; never recurse into archive paths.
            Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        } catch (e: Exception) {
            logger.warn("Could not remove artifact staging directory {}: {}", directory, e.message)
        }
    }
}

internal class ArtifactPayloadLimitException(limit: Long) :
    IllegalArgumentException("Selected file exceeds maxFileBytes ($limit)")

internal fun publishedFileName(path: String): String {
    // Embedded double quotes are not transported consistently by the Windows Docker CLI.
    require(path.startsWith('/') && path != "/" && path.length <= 4096 &&
        path.drop(1).split('/').all { it.isNotEmpty() && it != "." && it != ".." } &&
        path.none { it == '\\' || it == ':' || it == '"' || it.isISOControl() }) {
        "Publish path must be a normalized absolute container file path without backslashes, colons, double quotes, or control characters"
    }
    return path.substringAfterLast('/')
}
