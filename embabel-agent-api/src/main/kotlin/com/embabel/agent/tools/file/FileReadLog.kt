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

import java.time.Instant

/**
 * Tracks access context
 */
interface FileAccessLog {
    fun getPathsAccessed(): List<String>
}

/**
 * Represents reads of a file
 */
data class FileReads(
    val path: String,
    val reads: List<Instant> = emptyList(),
) {

    fun count() = reads.size
}

/**
 * The FileReadLog can be useful for stats
 * and to understand the context that has been accessed
 */
interface FileReadLog {

    fun flushReads()

    fun recordRead(path: String)

    fun getReads(): List<FileReads>

    fun getPathsRead(): List<String> = getReads().map { it.path }
}

/**
 * Convenient file change log implementation that stores changes in memory
 * and correctly handles duplicates.
 */
class DefaultFileReadLog(
    private val reads: MutableMap<String, FileReads> = mutableMapOf(),
) : FileReadLog {

    override fun flushReads() {
        reads.clear()
    }

    override fun recordRead(path: String) {
        val currentReads = reads.getOrDefault(path, FileReads(path))
        val updatedReads = currentReads.copy(reads = currentReads.reads + Instant.now())
        reads[path] = updatedReads
    }

    override fun getReads(): List<FileReads> {
        return reads.values.toList().sortedBy { it.path }
    }
}
