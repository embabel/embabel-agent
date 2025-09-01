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
import io.github.classgraph.ClassGraph

/**
 * Extracts API reference information from the project classpath using ClassGraph.
 */
class ClassGraphApiReferenceExtractor {

    fun fromProjectClasspath(
        name: String,
        acceptedPackages: Set<String>,
        rejectedPackages: Set<String> = DEFAULT_EXCLUDED_PACKAGES,
    ): Api {
        val classGraph = ClassGraph()
            .enableClassInfo()
            .enableMethodInfo()
            .enableAnnotationInfo()
            .ignoreClassVisibility()
            .ignoreMethodVisibility()
            .acceptPackages(*acceptedPackages.toTypedArray())
            .rejectPackages(*rejectedPackages.toTypedArray())

        return classGraph.scan().use { scanResult ->
            val apiClasses = scanResult.allClasses
                .filter { classInfo ->
                    classInfo.isPublic &&
                            !classInfo.isAnonymousInnerClass &&
                            !classInfo.isSynthetic &&
                            !classInfo.simpleName.endsWith("Kt") &&
                            !classInfo.simpleName.contains("Companion") &&  // Filter out Kotlin companion objects
                            !classInfo.simpleName.contains("$")             // Filter out other synthetic classes
                }
                .map { classInfo ->
                    ApiClass(
                        name = classInfo.simpleName,
                        packageName = classInfo.packageName ?: "",
                        type = when {
                            classInfo.isInterface -> "interface"
                            classInfo.isEnum -> "enum"
                            classInfo.isAnnotation -> "annotation"
                            else -> "class"
                        },
                        methods = classInfo.methodInfo
                            .filter { it.isPublic && !it.isConstructor && !it.isSynthetic }
                            .map { methodInfo ->
                                ApiMethod(
                                    name = methodInfo.name,
                                    parameters = methodInfo.parameterInfo.map { param ->
                                        val typeStr = param.typeSignature?.toStringWithSimpleNames()
                                            ?: param.typeDescriptor.toStringWithSimpleNames()
                                        "${param.name ?: "param"}: $typeStr"
                                    },
                                    returnType = methodInfo.typeSignature?.resultType?.toStringWithSimpleNames()
                                        ?: methodInfo.typeDescriptor.resultType.toStringWithSimpleNames(),
                                    annotations = methodInfo.annotationInfo.map { it.name }
                                )
                            },
                        annotations = classInfo.annotationInfo.map { it.name },
                        superTypes = buildList {
                            classInfo.superclass?.let { if (it.name != "java.lang.Object") add(it.simpleName) }
                            addAll(classInfo.interfaces.map { it.simpleName })
                        }
                    )
                }

            val totalMethods = apiClasses.sumOf { it.methods.size }
            Api(name, apiClasses, apiClasses.size, totalMethods)
        }
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
