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

import com.embabel.agent.api.common.LlmReference
import org.springframework.ai.tool.annotation.Tool

class ApiReference(
    private val api: Api,
    private val classLimit: Int = 100,
) : LlmReference {

    override val name = api.name

    override val description =
        "API reference for ${api.name} with ${api.totalClasses} classes and ${api.totalMethods} methods."

    override fun contribution(): String {
        if (api.classes.size > classLimit) {
            return """
                API reference is too large to include here (contains ${api.totalClasses} classes and ${api.totalMethods} methods).
                Use the tools `findClassSignature` and `findPackageSignature` to look up specific classes or packages by their fully qualified names (FQN).
            """.trimIndent()
        }
        return """
            The following is an API reference for ${api.totalClasses} classes and ${api.totalMethods} methods.
            Use this reference to answer questions about the API, find class or package signatures, and understand how to use the classes and methods.
            You can also use the tools `findClassSignature` and `findPackageSignature` to look up specific classes or packages by their fully qualified names (FQN).

            ${formatAsText()}
        """.trimIndent()
    }

    fun formatAsText(): String {
        val sb = StringBuilder()
        sb.appendLine("API Reference - ${api.totalClasses} classes, ${api.totalMethods} methods")
        sb.appendLine()

        for (clazz in api.classes.sortedBy { "${it.packageName}.${it.name}" }) {
            sb.appendLine()
            sb.append(formatClass(clazz))
            sb.appendLine()
        }
        return sb.toString()
    }

    @Tool(description = "find the signature of a class by FQN")
    fun findClassSignature(fqn: String): String {
        val clazz = api.classes.find { "${it.packageName}.${it.name}" == fqn }
        return clazz?.let { formatClass(it) } ?: "Class not found: $fqn"
    }

    @Tool(description = "find the signature of a package by FQN")
    fun findPackageSignature(packageName: String): String {
        val classesInPackage = api.classes.filter { it.packageName == packageName }
        if (classesInPackage.isEmpty()) {
            return "Package not found: $packageName"
        }

        val sb = StringBuilder()
        sb.appendLine("Package: $packageName")
        sb.appendLine("Classes:")
        for (clazz in classesInPackage.sortedBy { it.name }) {
            sb.appendLine("  - ${clazz.name} (${clazz.type})")
        }
        return sb.toString()
    }

    fun formatClass(clazz: ApiClass): String {
        val sb = StringBuilder()

        // Class header
        sb.append("${clazz.packageName}.${clazz.name}")
        if (clazz.type != "class") sb.append(" (${clazz.type})")
        sb.appendLine()

        // Class comment
        clazz.comment?.let { comment ->
            sb.appendLine("  // $comment")
        }

        // Annotations
//        if (clazz.annotations.isNotEmpty()) {
//            sb.appendLine("  @${clazz.annotations.joinToString(" @") { it.substringAfterLast('.') }}")
//        }

        // Super types
        if (clazz.superTypes.isNotEmpty()) {
            sb.appendLine("  extends/implements: ${clazz.superTypes.joinToString(", ")}")
        }

        // Methods
        clazz.methods.forEach { method ->
            val params = method.parameters.joinToString(", ")
            val annotations = if (method.annotations.isNotEmpty()) {
                " @${method.annotations.joinToString(" @") { it.substringAfterLast('.') }}"
            } else ""

            // Method with comment
            method.comment?.let { comment ->
                sb.appendLine("    // $comment")
            }
            sb.appendLine("  ${method.name}($params): ${method.returnType}$annotations")
        }
        return sb.toString()
    }
}
