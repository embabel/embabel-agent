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
package com.embabel.agent.rag.ingestion

import com.embabel.agent.rag.model.LeafSection
import com.embabel.agent.rag.model.MaterializedDocument
import com.embabel.agent.tools.file.FileReadTools
import com.embabel.common.util.VisualizableTask
import org.apache.tika.detect.DefaultDetector
import org.apache.tika.detect.Detector
import org.apache.tika.exception.ZeroByteFileException
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.parser.ParseContext
import org.apache.tika.sax.BodyContentHandler
import org.slf4j.LoggerFactory
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.Resource
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.Charset
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.UnsupportedCharsetException
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * Reads various content types using Apache Tika and extracts LeafSection objects containing the actual content.
 * Can read local files or URLs via Spring Resource loading.
 *
 * This reader can handle Markdown, HTML, PDF, Word documents, and many other formats
 * supported by Apache Tika and returns a list of LeafSection objects that can be processed for RAG.
 */
class TikaHierarchicalContentReader : HierarchicalContentReader {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val parser = AutoDetectParser()
    private val detector: Detector = DefaultDetector()

    // Content format parsers
    private val plainTextParser = PlainTextContentParser(logger)
    private val markdownParser = MarkdownContentParser(logger)
    private val htmlParser = HtmlContentParser(logger, plainTextParser)

    /**
     * Parse content from a URL with proper HTTP headers to avoid 403 errors.
     * Overrides the default implementation to add browser-like headers for HTTP/HTTPS URLs.
     */
    override fun parseUrl(url: String): MaterializedDocument {
        // Check if it's an HTTP/HTTPS URL and handle it with proper headers
        if (url.startsWith("http://") || url.startsWith("https://")) {
            logger.debug("Fetching URL with custom headers: {}", url)

            val uri = URI(url)
            val connection = uri.toURL().openConnection() as HttpURLConnection

            try {
                // Set headers to look like a legitimate browser request
                // Note: Only requesting gzip and deflate which Java handles automatically
                // Brotli (br) is not supported natively and can cause stream detection issues
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                connection.setRequestProperty("Accept-Encoding", "gzip, deflate")
                connection.setRequestProperty("Connection", "keep-alive")
                connection.setRequestProperty("Upgrade-Insecure-Requests", "1")
                connection.connectTimeout = 30000 // 30 seconds
                connection.readTimeout = 30000 // 30 seconds

                connection.connect()

                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    logger.warn("Received HTTP {} for URL: {}", responseCode, url)
                    throw java.io.IOException("Server returned HTTP response code: $responseCode for URL: $url")
                }

                // Get the Content-Type from the response headers to help with detection
                val metadata = Metadata()
                val contentType = connection.contentType
                if (contentType != null) {
                    // Parse Content-Type header: "text/html; charset=UTF-8"
                    val parts = contentType.split(";").map { it.trim() }
                    metadata.set(TikaCoreProperties.CONTENT_TYPE_HINT, parts[0])

                    // Extract charset if present
                    val charsetPart = parts.find { it.startsWith("charset=", ignoreCase = true) }
                    if (charsetPart != null) {
                        val charset = charsetPart.substringAfter("=").trim()
                        metadata.set("charset", charset)
                        logger.debug("Server reported Content-Type: {} with charset: {}", parts[0], charset)
                    } else {
                        logger.debug("Server reported Content-Type: {}", parts[0])
                    }
                }

                // Handle Content-Encoding (gzip, deflate, etc.)
                val contentEncoding = connection.contentEncoding
                logger.debug("Content-Encoding: {}", contentEncoding ?: "none")

                return connection.inputStream.use { rawStream ->
                    // Decompress the stream if needed
                    val decompressedStream = when (contentEncoding?.lowercase()) {
                        "gzip" -> {
                            logger.debug("Decompressing gzip content")
                            GZIPInputStream(rawStream)
                        }
                        "deflate" -> {
                            logger.debug("Decompressing deflate content")
                            InflaterInputStream(rawStream)
                        }
                        else -> rawStream
                    }

                    parseContent(decompressedStream, url, metadata)
                }
            } finally {
                connection.disconnect()
            }
        }

        // For non-HTTP URLs, delegate to parseResource
        return parseResource(url)
    }

    override fun parseResource(
        resourcePath: String,
    ): MaterializedDocument {
        val resource: Resource = DefaultResourceLoader().getResource(resourcePath)

        // Set the resource name in metadata so markdown-by-extension detection works
        val metadata = Metadata()
        val filename = resource.filename ?: resourcePath.substringAfterLast('/')
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename)

        return resource.inputStream.use { inputStream ->
            parseContent(inputStream, resourcePath, metadata)
        }
    }

    override fun parseFile(
        file: File,
        url: String?,
    ): MaterializedDocument {
        logger.debug("Parsing file: {}", file.absolutePath)

        val metadata = Metadata()
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.name)

        return file.inputStream().use { inputStream ->
            parseContent(inputStream, metadata = metadata, uri = url ?: file.toURI().toString())
        }
    }

    override fun parseContent(
        inputStream: InputStream,
        uri: String,
    ) = parseContent(
        inputStream,
        uri,
        Metadata()
    )

    fun parseContent(
        inputStream: InputStream,
        uri: String,
        metadata: Metadata,
    ): MaterializedDocument {
        try {
            // Wrap in BufferedInputStream to support mark/reset for detection
            val bufferedStream = inputStream as? BufferedInputStream ?: BufferedInputStream(inputStream)

            // Autodetect content type if not explicitly provided
            val detectedType = if (metadata.get(TikaCoreProperties.CONTENT_TYPE_HINT) != null) {
                metadata.get(TikaCoreProperties.CONTENT_TYPE_HINT)
            } else {
                try {
                    val mediaType = detector.detect(bufferedStream, metadata)
                    mediaType.toString()
                } catch (e: Exception) {
                    // If detection fails (e.g., ArchiveException), default to text/plain
                    logger.debug("Content type detection failed: {}, defaulting to text/plain", e.message)
                    "text/plain"
                }
            }

            logger.debug("Detected content type: {}", detectedType)

            // For HTML content, read raw bytes to preserve HTML structure
            if (detectedType.contains("html")) {
                // Get charset from metadata (from HTTP Content-Type header or other source)
                val charset = getCharsetFromMetadata(metadata)
                val rawContent = bufferedStream.readBytes().toString(charset)
                return htmlParser.parse(rawContent, metadata, uri)
            }

            // For markdown files, read directly and skip Tika parsing to avoid archive detection issues
            val isMarkdownByExtension = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY)?.endsWith(".md") == true
            val isMarkdownByType = detectedType.contains("markdown")
            if (isMarkdownByExtension || isMarkdownByType) {
                val charset = getCharsetFromMetadata(metadata)
                val content = bufferedStream.readBytes().toString(charset)
                return markdownParser.parse(content, metadata, uri)
            }

            val handler = BodyContentHandler(-1) // No limit on content size
            val parseContext = ParseContext()

            parser.parse(bufferedStream, handler, metadata, parseContext)
            val content = handler.toString()

            logger.debug("Parsed content of type: {}, length: {}", detectedType, content.length)

            // Detect markdown by content patterns if MIME type detection fails
            val hasMarkdownHeaders = content.lines().any { line ->
                line.trim().matches(Regex("^#{1,6}\\s+.+"))
            }

            return when {
                hasMarkdownHeaders -> {
                    markdownParser.parse(content, metadata, uri)
                }

                else -> {
                    plainTextParser.parse(content, metadata, uri)
                }
            }

        } catch (e: ZeroByteFileException) {
            // Handle empty files gracefully
            logger.debug("Empty content detected, returning empty content root")
            return ContentFormatParserUtils.createEmptyContentRoot(metadata, uri)
        } catch (e: Exception) {
            logger.error("Error parsing content", e)
            return createErrorContentRoot(e.message ?: "Unknown parsing error", metadata, uri)
        }
    }

    /**
     * Extract charset from metadata, with fallback to UTF-8.
     * Handles invalid or unsupported charset names gracefully.
     */
    private fun getCharsetFromMetadata(metadata: Metadata): Charset {
        val charsetName = metadata.get("charset")
        if (charsetName != null) {
            try {
                return Charset.forName(charsetName)
            } catch (e: IllegalCharsetNameException) {
                logger.warn("Invalid charset name '{}', falling back to UTF-8", charsetName)
            } catch (e: UnsupportedCharsetException) {
                logger.warn("Unsupported charset '{}', falling back to UTF-8", charsetName)
            }
        }
        return Charsets.UTF_8
    }

    private fun createErrorContentRoot(
        errorMessage: String,
        metadata: Metadata,
        uri: String,
    ): MaterializedDocument {
        val rootId = UUID.randomUUID().toString()
        val leafId = UUID.randomUUID().toString()
        val metadataMap = ContentFormatParserUtils.extractMetadataMap(metadata)
        val errorSection = LeafSection(
            id = leafId,
            uri = uri,
            title = "Parse Error",
            text = "Error parsing content: $errorMessage",
            parentId = rootId,
            metadata = metadataMap + mapOf(
                "error" to errorMessage,
                "root_document_id" to rootId,
                "container_section_id" to rootId,
                "leaf_section_id" to leafId
            )
        )

        return MaterializedDocument(
            id = rootId,
            uri = uri,
            title = "Parse Error",
            ingestionTimestamp = java.time.Instant.now(),
            children = listOf(errorSection),
            metadata = metadataMap + mapOf("error" to errorMessage)
        )
    }


    override fun parseFromDirectory(
        fileTools: FileReadTools,
        config: DirectoryParsingConfig,
    ): DirectoryParsingResult {
        val startTime = Instant.now()

        logger.info("Starting directory parsing with config: {}", config)

        return try {
            val files = discoverFiles(fileTools, config)
            logger.info("Discovered {} files for parsing", files.size)

            processFiles(fileTools, files, config, startTime)

        } catch (e: Exception) {
            logger.error("Failed to parse directory '{}': {}", config.relativePath, e.message, e)
            DirectoryParsingResult(
                totalFilesFound = 0,
                filesProcessed = 0,
                filesSkipped = 0,
                filesErrored = 1,
                contentRoots = emptyList(),
                processingTime = Duration.between(startTime, Instant.now()),
                errors = listOf("Directory parsing failed: ${e.message}")
            )
        }
    }

    /**
     * Parse a single file using the configured reader.
     *
     * @param fileTools The FileTools instance to use for file access
     * @param filePath The relative path to the file to parse
     * @return Result of the parsing operation, or null if the file couldn't be processed
     */
    fun parseFile(
        fileTools: FileReadTools,
        filePath: String,
    ): MaterializedDocument? {
        return try {
            logger.debug("Parsing single file: {}", filePath)

            // Validate file exists and is readable through FileTools
            val content = fileTools.safeReadFile(filePath)
            if (content == null) {
                logger.warn("Could not read file: {}", filePath)
                return null
            }

            // Use file URI for local files - convert to proper URI format
            val fileUri = fileTools.resolvePath(filePath).toUri().toString()
            val result = parseResource(fileUri)

            logger.info(
                "Successfully parsed file '{}' - {} sections extracted",
                filePath, result.leaves().count()
            )

            result

        } catch (e: Exception) {
            logger.error("Failed to parse file '{}': {}", filePath, e.message, e)
            null
        }
    }

    /**
     * Discover all files in the directory structure that match the parsing criteria.
     */
    private fun discoverFiles(
        fileTools: FileReadTools,
        config: DirectoryParsingConfig,
    ): List<String> {
        val files = mutableListOf<String>()
        val startPath = config.relativePath.ifEmpty { "" }

        logger.debug("Discovering files in directory: {}", startPath)

        try {
            discoverFilesRecursive(fileTools, startPath, files, config, 0)
        } catch (e: Exception) {
            logger.error("Error discovering files in '{}': {}", startPath, e.message, e)
        }

        logger.debug("Discovered {} files in directory '{}'", files.size, startPath)
        return files
    }

    /**
     * Recursively discover files in a directory structure.
     */
    private fun discoverFilesRecursive(
        fileTools: FileReadTools,
        currentPath: String,
        files: MutableList<String>,
        config: DirectoryParsingConfig,
        depth: Int,
    ) {
        if (depth > config.maxDepth) {
            logger.debug("Reached max depth {} at path '{}'", config.maxDepth, currentPath)
            return
        }

        try {
            val entries = fileTools.listFiles(currentPath)

            for (entry in entries) {
                val isDirectory = entry.startsWith("d:")
                val name = entry.substring(2) // Remove "d:" or "f:" prefix
                val fullPath = if (currentPath.isEmpty()) name else "$currentPath/$name"

                if (isDirectory) {
                    // Check if directory should be excluded
                    if (name in config.excludedDirectories) {
                        logger.debug("Skipping excluded directory: {}", fullPath)
                        continue
                    }

                    // Recurse into subdirectory
                    discoverFilesRecursive(fileTools, fullPath, files, config, depth + 1)
                } else {
                    // Check file extension
                    val extension = name.substringAfterLast('.', "").lowercase()
                    if (extension in config.includedExtensions) {
                        // Check file size
                        val resolvedPath = fileTools.resolvePath(fullPath)
                        if (Files.exists(resolvedPath)) {
                            val size = Files.size(resolvedPath)
                            if (size <= config.maxFileSize) {
                                files.add(fullPath)
                                logger.trace("Added file for parsing: {} (size: {} bytes)", fullPath, size)
                            } else {
                                logger.debug(
                                    "Skipping large file: {} (size: {} bytes, limit: {} bytes)",
                                    fullPath, size, config.maxFileSize
                                )
                            }
                        }
                    } else {
                        logger.trace("Skipping file with excluded extension: {} (extension: {})", fullPath, extension)
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Could not list files in directory '{}': {}", currentPath, e.message)
        }
    }

    /**
     * Process the discovered files for parsing.
     */
    private fun processFiles(
        fileTools: FileReadTools,
        files: List<String>,
        config: DirectoryParsingConfig,
        startTime: Instant,
    ): DirectoryParsingResult {
        var filesProcessed = 0
        var filesSkipped = 0
        var filesErrored = 0
        val contentRoots = mutableListOf<MaterializedDocument>()
        val errors = mutableListOf<String>()

        logger.info("Processing {} files for parsing", files.size)

        fun logProgress(current: Int) {
            val progress = VisualizableTask(
                name = "Parsing files",
                current = current,
                total = files.size
            )
            logger.info(progress.createProgressBar())
        }

        logProgress(0)

        for ((index, filePath) in files.withIndex()) {
            try {
                val result = parseFile(fileTools, filePath)
                if (result != null) {
                    contentRoots.add(result)
                    filesProcessed++
                    logger.debug(
                        "Successfully processed file {} ({}/{}): {} sections",
                        filePath, index + 1, files.size, result.leaves().count()
                    )
                } else {
                    filesSkipped++
                    logger.debug("Skipped file {} ({}/{})", filePath, index + 1, files.size)
                }
            } catch (e: Exception) {
                filesErrored++
                val error = "Error processing file '$filePath': ${e.message}"
                errors.add(error)
                logger.error(error, e)
            }
            logProgress(index + 1)
        }

        val processingTime = Duration.between(startTime, Instant.now())

        logger.info("Directory parsing completed in {} ms", processingTime.toMillis())
        logger.info("Files processed: {}, skipped: {}, errors: {}", filesProcessed, filesSkipped, filesErrored)
        logger.info("Total sections extracted: {}", contentRoots.sumOf { it.leaves().count() })

        return DirectoryParsingResult(
            totalFilesFound = files.size,
            filesProcessed = filesProcessed,
            filesSkipped = filesSkipped,
            filesErrored = filesErrored,
            contentRoots = contentRoots,
            processingTime = processingTime,
            errors = errors
        )
    }
}
