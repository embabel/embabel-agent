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

import com.embabel.common.util.StringTransformer

/**
 * Read and Write file tools. Extend FileReadTools for safe read only use
 */
interface FileTools : FileReadTools, FileWriteTools {

    override fun getPathsAccessed(): List<String> = (getPathsRead() + getChanges().map { it.path }).distinct()

    companion object {

        /**
         * Create a FileReadTools instance with the given root directory.
         */
        @JvmStatic
        @JvmOverloads
        fun readOnly(
            root: String,
            fileContentTransformers: List<StringTransformer> = emptyList(),
        ): FileReadTools = DefaultFileReadTools(root, fileContentTransformers)

        /**
         * Create a readwrite FileTools instance with the given root directory.
         */
        @JvmStatic
        @JvmOverloads
        fun readWrite(
            root: String,
            fileContentTransformers: List<StringTransformer> = emptyList(),
        ): FileTools = DefaultFileTools(root, fileContentTransformers)
    }
}


private class DefaultFileTools(
    override val root: String,
    override val fileContentTransformers: List<StringTransformer> = emptyList(),
) : FileTools, FileReadLog by DefaultFileReadLog(), FileChangeLog by DefaultFileChangeLog()
