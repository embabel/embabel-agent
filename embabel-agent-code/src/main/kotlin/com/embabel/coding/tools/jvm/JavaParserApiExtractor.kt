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
package com.embabel.coding.tools.jvm

import com.embabel.coding.tools.api.Api
import com.embabel.coding.tools.api.ApiClass
import com.embabel.coding.tools.api.ApiMethod
import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Modifier
import com.github.javaparser.ast.body.*
import com.github.javaparser.ast.expr.AnnotationExpr
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

/**
 * Extracts API reference information from Java/Kotlin source code using JavaParser.
 */
class JavaParserApiExtractor {

    private val logger = LoggerFactory.getLogger(JavaParserApiExtractor::class.java)
    private val javaParser: JavaParser

    init {
        val typeSolver = CombinedTypeSolver().apply {
            add(ReflectionTypeSolver())
        }

        val config = ParserConfiguration().apply {
            setSymbolResolver(JavaSymbolSolver(typeSolver))
            setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
        }

        javaParser = JavaParser(config)
    }

    /**
     * Extract API information from source code in the given directory
     */
    fun fromSourceDirectory(
        name: String,
        sourceDir: Path,
        acceptedPackages: Set<String> = emptySet(),
        rejectedPackages: Set<String> = DEFAULT_EXCLUDED_PACKAGES,
    ): Api {
        if (!Files.exists(sourceDir)) {
            logger.warn("Source directory does not exist: $sourceDir")
            return Api(name, emptyList(), 0, 0)
        }

        val javaFiles = findJavaFiles(sourceDir)
        val apiClasses = mutableListOf<ApiClass>()

        for (javaFile in javaFiles) {
            try {
                val parseResult = javaParser.parse(javaFile)
                if (parseResult.isSuccessful) {
                    parseResult.result.ifPresent { compilationUnit ->
                        val extractedClasses = extractClassesFromCompilationUnit(
                            compilationUnit,
                            acceptedPackages,
                            rejectedPackages
                        )
                        apiClasses.addAll(extractedClasses)
                    }
                } else {
                    logger.warn("Failed to parse: $javaFile - ${parseResult.problems}")
                }
            } catch (e: Exception) {
                logger.warn("Error parsing file $javaFile: ${e.message}")
            }
        }

        val totalMethods = apiClasses.sumOf { it.methods.size }
        return Api(name, apiClasses, apiClasses.size, totalMethods)
    }

    private fun findJavaFiles(sourceDir: Path): List<Path> {
        return try {
            Files.walk(sourceDir)
                .asSequence()
                .filter { Files.isRegularFile(it) }
                .filter { it.toString().endsWith(".java") }
                .toList()
        } catch (e: Exception) {
            logger.warn("Error walking source directory $sourceDir: ${e.message}")
            emptyList()
        }
    }

    private fun extractClassesFromCompilationUnit(
        compilationUnit: CompilationUnit,
        acceptedPackages: Set<String>,
        rejectedPackages: Set<String>,
    ): List<ApiClass> {
        val packageName = compilationUnit.packageDeclaration
            .map { it.nameAsString }
            .orElse("")

        // Filter by package acceptance/rejection rules
        if (acceptedPackages.isNotEmpty() && acceptedPackages.none { packageName.startsWith(it) }) {
            return emptyList()
        }

        if (rejectedPackages.any { packageName.startsWith(it) }) {
            return emptyList()
        }

        val apiClasses = mutableListOf<ApiClass>()

        // Extract classes, interfaces, enums, annotations
        compilationUnit.types.forEach { typeDeclaration ->
            when (typeDeclaration) {
                is ClassOrInterfaceDeclaration -> {
                    if (shouldIncludeType(typeDeclaration)) {
                        apiClasses.add(extractClassOrInterface(typeDeclaration, packageName))
                    }
                }
                is EnumDeclaration -> {
                    if (shouldIncludeType(typeDeclaration)) {
                        apiClasses.add(extractEnum(typeDeclaration, packageName))
                    }
                }
                is AnnotationDeclaration -> {
                    if (shouldIncludeType(typeDeclaration)) {
                        apiClasses.add(extractAnnotation(typeDeclaration, packageName))
                    }
                }
            }
        }

        return apiClasses
    }

    private fun shouldIncludeType(typeDeclaration: TypeDeclaration<*>): Boolean {
        return typeDeclaration.isPublic &&
               !typeDeclaration.nameAsString.contains("$") && // Skip inner/synthetic classes
               !typeDeclaration.nameAsString.endsWith("Kt")    // Skip Kotlin generated classes
    }

    private fun extractClassOrInterface(
        classDeclaration: ClassOrInterfaceDeclaration,
        packageName: String,
    ): ApiClass {
        val methods = classDeclaration.methods
            .filter { it.isPublic && !it.isStatic }
            .map { extractMethod(it) }

        val superTypes = mutableListOf<String>()

        // Add extended types
        classDeclaration.extendedTypes.forEach { extendedType ->
            superTypes.add(extendedType.nameAsString)
        }

        // Add implemented interfaces
        classDeclaration.implementedTypes.forEach { implementedType ->
            superTypes.add(implementedType.nameAsString)
        }

        return ApiClass(
            name = classDeclaration.nameAsString,
            packageName = packageName,
            type = if (classDeclaration.isInterface) "interface" else "class",
            methods = methods,
            annotations = extractAnnotations(classDeclaration.annotations),
            superTypes = superTypes,
            comment = extractComment(classDeclaration)
        )
    }

    private fun extractEnum(enumDeclaration: EnumDeclaration, packageName: String): ApiClass {
        val methods = enumDeclaration.methods
            .filter { it.isPublic && !it.isStatic }
            .map { extractMethod(it) }

        return ApiClass(
            name = enumDeclaration.nameAsString,
            packageName = packageName,
            type = "enum",
            methods = methods,
            annotations = extractAnnotations(enumDeclaration.annotations),
            superTypes = emptyList(),
            comment = extractComment(enumDeclaration)
        )
    }

    private fun extractAnnotation(annotationDeclaration: AnnotationDeclaration, packageName: String): ApiClass {
        // Annotation methods are represented as annotation members
        val methods = annotationDeclaration.members
            .filterIsInstance<AnnotationMemberDeclaration>()
            .map { member ->
                ApiMethod(
                    name = member.nameAsString,
                    parameters = emptyList(), // Annotation methods don't have parameters
                    returnType = member.type.asString(),
                    annotations = extractAnnotations(member.annotations),
                    comment = extractComment(member)
                )
            }

        return ApiClass(
            name = annotationDeclaration.nameAsString,
            packageName = packageName,
            type = "annotation",
            methods = methods,
            annotations = extractAnnotations(annotationDeclaration.annotations),
            superTypes = emptyList(),
            comment = extractComment(annotationDeclaration)
        )
    }

    private fun extractMethod(methodDeclaration: MethodDeclaration): ApiMethod {
        val parameters = methodDeclaration.parameters.map { param ->
            val paramName = param.nameAsString
            val paramType = param.type.asString()
            "$paramName: $paramType"
        }

        return ApiMethod(
            name = methodDeclaration.nameAsString,
            parameters = parameters,
            returnType = methodDeclaration.type.asString(),
            annotations = extractAnnotations(methodDeclaration.annotations),
            comment = extractComment(methodDeclaration)
        )
    }

    private fun extractAnnotations(annotations: List<AnnotationExpr>): List<String> {
        return annotations.map { annotation ->
            annotation.nameAsString
        }
    }

    private fun extractComment(node: com.github.javaparser.ast.Node): String? {
        return node.comment.map { comment ->
            when (comment) {
                is com.github.javaparser.ast.comments.JavadocComment -> {
                    // For Javadoc comments, clean up the formatting
                    cleanJavadocComment(comment.content)
                }
                is com.github.javaparser.ast.comments.BlockComment -> {
                    // For block comments, clean up the formatting
                    cleanBlockComment(comment.content)
                }
                is com.github.javaparser.ast.comments.LineComment -> {
                    // For line comments, just trim
                    comment.content.trim()
                }
                else -> comment.content.trim()
            }
        }.orElse(null)
    }

    private fun cleanJavadocComment(content: String): String {
        return content
            .lines()
            .map { line ->
                // Remove leading * and whitespace from each line
                line.trimStart().removePrefix("*").trim()
            }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
    }

    private fun cleanBlockComment(content: String): String {
        return content
            .lines()
            .map { line ->
                // Remove leading * and whitespace from each line
                line.trimStart().removePrefix("*").trim()
            }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
    }

    companion object {
        val DEFAULT_EXCLUDED_PACKAGES = setOf(
            "java.",
            "javax.",
            "kotlin.",
            "kotlinx.",
            "org.jetbrains.",
            "org.springframework.",
            "org.apache.",
            "com.sun.",
            "sun.",
            "com.embabel.agent.spi",
            "com.embabel.agent.config",
            "com.embabel.agent.web",
        )
    }
}
