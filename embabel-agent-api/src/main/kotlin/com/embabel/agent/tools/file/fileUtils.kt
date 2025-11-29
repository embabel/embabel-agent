/*
 * Copyright 2024-2025 Embabel Software, Inc.
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

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves a relative path against the root directory
 * Prevents path traversal attacks by ensuring the resolved path is within the root
 */
internal fun resolvePath(
    root: String,
    path: String,
): Path {
    val basePath = Paths.get(root).toAbsolutePath().normalize()
    val resolvedPath = basePath.resolve(path).normalize().toAbsolutePath()

    if (!resolvedPath.startsWith(basePath)) {
        throw SecurityException("Path traversal attempt detected: $path, root=$root, resolved='$resolvedPath', base=$'basePath'")
    }
    return resolvedPath
}

/**
 * Resolves a path and validates that it exists and is a regular file
 * @throws IllegalArgumentException if the file doesn't exist or isn't a regular file
 */
internal fun resolveAndValidateFile(
    root: String,
    path: String,
): Path {
    val resolvedPath = resolvePath(root = root, path = path)
    if (!Files.exists(resolvedPath)) {
        throw IllegalArgumentException("File does not exist: $path, root=$root")
    }
    if (!Files.isRegularFile(resolvedPath)) {
        throw IllegalArgumentException("Path is not a regular file: $path, root=$root")
    }
    return resolvedPath
}

/**
 * Format a file size in bytes to a human-readable string using appropriate units.
 */
internal fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes bytes"
        bytes < 1024 * 1024 -> "%.2f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}
