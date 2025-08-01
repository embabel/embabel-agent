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

import com.embabel.common.util.loggerFor

enum class FileModificationType {
    CREATE, EDIT, DELETE, APPEND, CREATE_DIRECTORY
}

data class FileModification(
    val path: String,
    val type: FileModificationType,
)

interface FileChangeLog {

    fun flushChanges()

    fun recordChange(c: FileModification)

    fun getChanges(): List<FileModification>
}

/**
 * Convenient file change log implementation that stores changes in memory
 * and correctly handles duplicates.
 */
class DefaultFileChangeLog(
    private val changes: MutableList<FileModification> = mutableListOf(),
) : FileChangeLog {

    override fun flushChanges() {
        changes.clear()
        loggerFor<FileWriteTools>().debug("Flushed file changes")
        changes.clear()
    }

    override fun recordChange(c: FileModification) {
        val existingChange = changes.find { it.path == c.path }
        if (existingChange != null) {
            if (existingChange.type == c.type) {
                // If the same change is already recorded, do not add it again
                loggerFor<FileWriteTools>().debug("Change already recorded: {}", c)
            } else {
                // If a different type of change is recorded, update it
                changes.remove(existingChange)
                changes.add(c)
            }
        } else {
            changes.add(c)
        }
        loggerFor<FileWriteTools>().debug("Recorded file change: {}", c)
    }

    override fun getChanges(): List<FileModification> = changes.toList()
}
