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
package com.embabel.agent.tools.file

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.api.common.support.SelfToolPublisher
import com.embabel.agent.tools.DirectoryBased
import com.embabel.common.util.StringTransformer
import com.embabel.common.util.loggerFor
import java.io.File
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * LLM-ready ToolCallbacks and convenience methods for file operations.
 * Use at your own risk: This makes changes to your host machine!!
 */
interface FileReadTools : DirectoryBased, FileReadLog, FileAccessLog, SelfToolPublisher {

    /**
     * Provide sanitizers that run on file content before returning it.
     * They must be sure not to change any content that may need to be replaced
     * as this will break editing if editing is done in the same session.
     */
    val fileContentTransformers: List<StringTransformer>

    override fun getPathsAccessed(): List<String> = getPathsRead()

    /**
     * Does this file exist?
     */
    fun exists(): Boolean {
        return Files.exists(resolvePath(""))
    }

    /**
     * Count the total number of files in the repository (excluding .git directory).
     * Uses FileVisitor for cross-platform compatibility (Windows and Linux).
     */
    @LlmTool(description = "Count the number of files in the repository, excluding .git directory")
    fun fileCount(): Int {
        return try {
            val rootPath = resolvePath("")
            val fileCount = AtomicInteger(0)

            Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    val dirName = dir.fileName?.toString()
                    return if (dirName == ".git") {
                        // Skip entire .git directory and all its contents
                        FileVisitResult.SKIP_SUBTREE
                    } else {
                        FileVisitResult.CONTINUE
                    }
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    // Only count regular files
                    if (attrs.isRegularFile) {
                        fileCount.incrementAndGet()
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult {
                    // Handle permission issues gracefully (common on Windows)
                    loggerFor<FileReadTools>().warn("Warning: Could not access file: {} ({})", file, exc.message)
                    return FileVisitResult.CONTINUE
                }
            })

            fileCount.get()
        } catch (e: Exception) {
            loggerFor<FileReadTools>().error("Failed to count files", e)
            0
        }
    }

    @LlmTool(description = "Find files using glob patterns. Return absolute paths")
    fun findFiles(glob: String): List<String> = findFiles(glob, findHighest = false)

    /**
     * Find files using glob patterns.
     * @param glob the glob pattern to match files against
     * @param findHighest if true, only the highest matching file in the directory tree will be returned
     * For example, if you want to find all Maven projects by looking for pom.xml files.
     * @return list of absolute file paths matching the glob pattern
     */
    fun findFiles(
        glob: String,
        findHighest: Boolean,
    ): List<String> {
        val basePath = Paths.get(root).toAbsolutePath().normalize()
        // Ripgrep/gitignore '**' semantics ('**/' matches zero or more directories), preserving every
        // other NIO glob feature ({...}, [...], ?). See Globs.
        val matches = globMatcher(glob)
        val results = mutableListOf<String>()

        if (!findHighest) {
            return Files.walk(basePath).use { paths ->
                paths.filter { Files.isRegularFile(it) }
                    .filter { matches(basePath.relativize(it)) }
                    .map { it.toAbsolutePath().toString() }
                    .toList()
            }
        }

        // We need to process directories breadth-first to find the highest matches
        val processedDirs = mutableSetOf<String>()
        val queue = ArrayDeque<Path>()
        queue.offer(basePath)

        while (queue.isNotEmpty()) {
            val dir = queue.poll()
            val dirStr = dir.toAbsolutePath().toString()

            // Skip if we've already processed this directory
            if (dirStr in processedDirs) {
                continue
            }
            processedDirs.add(dirStr)

            // First, check if this directory itself matches
            if (Files.isRegularFile(dir) && matches(basePath.relativize(dir))) {
                results.add(dirStr)
                continue
            }

            try {
                // Look for matches in this directory
                val matchesInDir = mutableListOf<String>()
                val subdirs = mutableListOf<Path>()

                Files.newDirectoryStream(dir).use { stream ->
                    stream.forEach { entry ->
                        if (Files.isDirectory(entry)) {
                            subdirs.add(entry)
                        } else if (matches(basePath.relativize(entry))) {
                            matchesInDir.add(entry.toAbsolutePath().toString())
                        }
                    }
                }

                if (matchesInDir.isNotEmpty()) {
                    // Found matches in this directory, add them and don't process subdirectories
                    results.addAll(matchesInDir)

                    // Mark all subdirectories as processed so we don't look into them
                    subdirs.forEach { subdir ->
                        processedDirs.add(subdir.toAbsolutePath().toString())
                    }
                } else {
                    // No matches in this directory, so process subdirectories
                    queue.addAll(subdirs)
                }
            } catch (_: IOException) {
                loggerFor<FileReadTools>().warn("Failed to read directory at {}", dirStr)
                continue
            }
        }

        return results
    }

    /**
     * Use for safe reading of files. Returns null if the file doesn't exist or is not readable.
     */
    fun safeReadFile(path: String): String? = try {
        readFile(path)
    } catch (e: Exception) {
        loggerFor<FileReadTools>().warn("Failed to read file at {}: {}", path, e.message)
        null
    }

    @LlmTool(description = "Return the size of the file at the relative path as a string. Use the appropriate unit. Say if the file does not exist")
    fun fileSize(path: String): String {
        val resolvedPath = resolvePath(path)
        if (!Files.exists(resolvedPath)) {
            return "File does not exist: $path"
        }
        if (!Files.isRegularFile(resolvedPath)) {
            return "Path is not a regular file: $path"
        }
        val bytes = Files.size(resolvedPath)
        return formatFileSize(bytes)
    }

    @LlmTool(description = "Read the whole file at the relative path")
    fun readFile(path: String): String {
        val resolvedPath = resolveAndValidateFile(path)
        val rawContent = Files.readString(resolvedPath)
        val transformedContent =
            StringTransformer.Companion.transform(rawContent, fileContentTransformers)

        loggerFor<FileReadTools>().debug(
            "Transformed {} content with {} sanitizers: Length went from {} to {}",
            path,
            fileContentTransformers.size,
            "%,d".format(rawContent.length),
            "%,d".format(transformedContent.length),
        )
        recordRead(path)
        return transformedContent
    }

    @LlmTool(description = "List files and directories at a given path. Prefix is f: for file or d: for directory")
    fun listFiles(path: String): List<String> {
        val resolvedPath = resolvePath(path)
        if (!Files.exists(resolvedPath)) {
            throw IllegalArgumentException("Directory does not exist: $path, root=$root")
        }
        if (!Files.isDirectory(resolvedPath)) {
            throw IllegalArgumentException("Path is not a directory: $path, root=$root")
        }

        return Files.list(resolvedPath).use { stream ->
            stream.map {
                val prefix = if (Files.isDirectory(it)) "d:" else "f:"
                prefix + it.fileName.toString()
            }.sorted().toList()
        }
    }

    fun resolvePath(path: String): Path {
        return resolvePath(root, path)
    }

    fun resolveAndValidateFile(path: String): Path {
        return resolveAndValidateFile(root, path)
    }

}

internal class DefaultFileReadTools(
    override val root: String,
    override val fileContentTransformers: List<StringTransformer> = emptyList(),
) : FileReadTools, FileReadLog by DefaultFileReadLog()

/**
 * A predicate matching relative paths against [pattern] with ripgrep/gitignore glob semantics.
 *
 * The glob is translated to a single regular expression (see [globToRegex]), so a double-star-slash
 * segment matches ZERO or more directories independently, however many appear in one pattern:
 * "a/&#42;&#42;/b/&#42;&#42;/c" matches every 0/1/n combination in a single pass. Every other glob feature is
 * preserved: "*" and "?" stay within a path segment, braces "{a,b}" and character classes "[a-z]"/"[!a]"
 * keep working. `regex:` patterns are matched verbatim via the platform [PathMatcher].
 */
fun globMatcher(pattern: String): (Path) -> Boolean {
    if (pattern.startsWith("regex:")) {
        val nio = FileSystems.getDefault().getPathMatcher(pattern)
        return { path -> nio.matches(path) }
    }
    val regex = Pattern.compile(globToRegex(pattern.removePrefix("glob:")))
    return { path -> regex.matcher(path.toString().replace(File.separatorChar, '/')).matches() }
}

/** Regex metacharacters that must be escaped when they appear literally in a glob. */
private const val REGEX_META = ".^$+{}[]()|"

/**
 * Translate a glob to a regex, mirroring the JDK glob translator (sun.nio.fs.Globs) with ONE change:
 * a double-star-slash segment becomes "(?:[^/]+/)*" (zero or more directory segments) instead of
 * forcing a literal separator. That literal separator is exactly why NIO skipped direct children and
 * root-level files. A bare or trailing double-star (not isolated before a slash) still becomes ".*",
 * crossing directories like NIO. "*" and "?" stay inside one segment; braces and classes are preserved.
 */
private fun globToRegex(glob: String): String {
    val regex = StringBuilder()
    var inGroup = false
    var i = 0
    while (i < glob.length) {
        when (val c = glob[i++]) {
            '\\' -> {
                require(i < glob.length) { "No character to escape at end of glob: $glob" }
                val next = glob[i++]
                if (next in REGEX_META || next in "\\*?[{") regex.append('\\')
                regex.append(next)
            }

            '/' -> regex.append('/')

            '[' -> {
                regex.append('[')
                when {
                    i < glob.length && glob[i] == '!' -> {
                        regex.append('^'); i++
                    }

                    i < glob.length && glob[i] == '^' -> {
                        regex.append("\\^"); i++
                    }
                }
                while (i < glob.length && glob[i] != ']') {
                    when (val cc = glob[i++]) {
                        '\\' -> {
                            regex.append('\\'); if (i < glob.length) regex.append(glob[i++])
                        }

                        '&' -> regex.append("\\&")
                        else -> regex.append(cc)
                    }
                }
                require(i < glob.length && glob[i] == ']') { "Missing ']' in glob: $glob" }
                regex.append(']'); i++
            }

            '{' -> {
                require(!inGroup) { "Nested '{' in glob: $glob" }
                inGroup = true
                regex.append("(?:")
            }

            '}' -> if (inGroup) {
                regex.append(')'); inGroup = false
            } else regex.append('}')

            ',' -> if (inGroup) regex.append('|') else regex.append(',')

            '*' -> {
                if (i < glob.length && glob[i] == '*') {
                    i++ // consume the second '*'
                    if (i < glob.length && glob[i] == '/') {
                        i++ // consume the slash: '**/' -> zero or more directory segments
                        regex.append("(?:[^/]+/)*")
                    } else {
                        regex.append(".*") // bare/trailing '**' crosses directories
                    }
                } else {
                    regex.append("[^/]*")
                }
            }

            '?' -> regex.append("[^/]")

            else -> {
                if (c in REGEX_META) regex.append('\\')
                regex.append(c)
            }
        }
    }
    require(!inGroup) { "Missing '}' in glob: $glob" }
    return regex.toString()
}
