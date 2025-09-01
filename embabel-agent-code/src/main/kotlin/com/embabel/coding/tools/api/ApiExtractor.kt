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
package com.embabel.coding.tools.api

import com.embabel.coding.tools.jvm.ClassGraphApiReferenceExtractor
import com.embabel.coding.tools.jvm.JavaParserApiExtractor
import java.nio.file.Path

/**
 * Unified API extractor that can work with both source code and compiled bytecode.
 */
class ApiExtractor {

    private val javaParserExtractor = JavaParserApiExtractor()
    private val classGraphExtractor = ClassGraphApiReferenceExtractor()

    /**
     * Extract API information from source code directory
     */
    fun fromSourceCode(
        name: String,
        sourceDir: Path,
        acceptedPackages: Set<String> = emptySet(),
        rejectedPackages: Set<String> = JavaParserApiExtractor.DEFAULT_EXCLUDED_PACKAGES,
    ): Api {
        return javaParserExtractor.fromSourceDirectory(name, sourceDir, acceptedPackages, rejectedPackages)
    }

    /**
     * Extract API information from project classpath (compiled bytecode)
     */
    fun fromClasspath(
        name: String,
        acceptedPackages: Set<String>,
        rejectedPackages: Set<String> = ClassGraphApiReferenceExtractor.DEFAULT_EXCLUDED_PACKAGES,
    ): Api {
        return classGraphExtractor.fromProjectClasspath(name, acceptedPackages, rejectedPackages)
    }

    /**
     * Combine API information from multiple sources
     */
    fun combine(name: String, apis: List<Api>): Api {
        val allClasses = apis.flatMap { it.classes }
            .distinctBy { "${it.packageName}.${it.name}" } // Remove duplicates

        val totalMethods = allClasses.sumOf { it.methods.size }

        return Api(name, allClasses, allClasses.size, totalMethods)
    }

    /**
     * Extract API from both source code and classpath, combining results
     */
    fun fromSourceAndClasspath(
        name: String,
        sourceDir: Path?,
        acceptedPackages: Set<String>,
        rejectedPackages: Set<String> = JavaParserApiExtractor.DEFAULT_EXCLUDED_PACKAGES,
    ): Api {
        val apis = mutableListOf<Api>()

        // Extract from source if available
        sourceDir?.let { dir ->
            val sourceApi = fromSourceCode(name + "-source", dir, acceptedPackages, rejectedPackages)
            if (sourceApi.classes.isNotEmpty()) {
                apis.add(sourceApi)
            }
        }

        // Extract from classpath
        val classpathApi = fromClasspath(name + "-classpath", acceptedPackages, rejectedPackages)
        if (classpathApi.classes.isNotEmpty()) {
            apis.add(classpathApi)
        }

        return if (apis.isNotEmpty()) {
            combine(name, apis)
        } else {
            Api(name, emptyList(), 0, 0)
        }
    }
}
