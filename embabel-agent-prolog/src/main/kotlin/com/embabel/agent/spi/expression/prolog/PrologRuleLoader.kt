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
package com.embabel.agent.spi.expression.prolog

import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream

/**
 * Loads Prolog rules from files or resources.
 */
class PrologRuleLoader {

    private val logger = LoggerFactory.getLogger(PrologRuleLoader::class.java)

    /**
     * Load rules from a file path.
     */
    fun loadFromFile(filePath: String): String {
        val file = File(filePath)
        if (!file.exists()) {
            throw IllegalArgumentException("Prolog file not found: $filePath")
        }
        return file.readText()
    }

    /**
     * Load rules from a classpath resource.
     * @param resourcePath Path to the resource, e.g., "/rules/auth.pl"
     * @param classLoader ClassLoader to use for loading the resource
     */
    fun loadFromResource(
        resourcePath: String,
        classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
    ): String {
        val stream = classLoader.getResourceAsStream(resourcePath.removePrefix("/"))
            ?: throw IllegalArgumentException("Prolog resource not found: $resourcePath")

        return stream.use { it.bufferedReader().readText() }
    }

    /**
     * Load rules from a resource relative to a class.
     * Looks in the same package as the class.
     * @param clazz The class whose package to search in
     * @param filename The filename to load
     */
    fun loadFromClassResource(
        clazz: Class<*>,
        filename: String,
    ): String {
        val packagePath = clazz.packageName.replace('.', '/')
        val resourcePath = "/$packagePath/$filename"

        logger.debug("Loading Prolog rules from: {}", resourcePath)

        return clazz.getResourceAsStream(resourcePath)?.use {
            it.bufferedReader().readText()
        } ?: throw IllegalArgumentException(
            "Prolog file not found: $filename for class ${clazz.simpleName} (searched at $resourcePath)"
        )
    }

    /**
     * Try to load rules from a class resource, returning null if not found.
     */
    fun tryLoadFromClassResource(
        clazz: Class<*>,
        filename: String,
    ): String? {
        return try {
            loadFromClassResource(clazz, filename)
        } catch (e: IllegalArgumentException) {
            logger.debug("Prolog file not found: $filename for class ${clazz.simpleName}")
            null
        }
    }

    /**
     * Load multiple rule files and combine them.
     */
    fun loadMultiple(vararg paths: String): String {
        return paths.map { loadFromFile(it) }.joinToString("\n\n")
    }

    /**
     * Load from an input stream.
     */
    fun loadFromStream(stream: InputStream): String {
        return stream.use { it.bufferedReader().readText() }
    }
}
