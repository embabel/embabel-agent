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
package com.embabel.agent.rag.model

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

/**
 * InvocationHandler that backs a [NamedEntity] instance with a property map.
 */
internal class NamedEntityInvocationHandler(
    private val properties: Map<String, Any?>,
    private val metadata: Map<String, Any?>,
    private val labels: Set<String>,
    private val entityData: NamedEntityData,
) : InvocationHandler {

    override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
        return when (val methodName = method.name) {
            "toString" -> "NamedEntityInstance(id=${properties["id"]}, name=${properties["name"]}, labels=$labels)"
            "hashCode" -> properties["id"].hashCode()
            "equals" -> {
                val other = args?.firstOrNull()
                when {
                    other === proxy -> true
                    other is NamedEntity -> other.id == properties["id"]
                    else -> false
                }
            }

            // NamedEntity / Retrievable interface methods
            "getId" -> properties["id"]
            "getName" -> properties["name"]
            "getDescription" -> properties["description"]
            "getUri" -> properties["uri"]
            "getMetadata" -> metadata
            "labels" -> labels
            "embeddableValue" -> entityData.embeddableValue()
            "infoString" -> {
                val verbose = args?.getOrNull(0) as? Boolean
                val indent = args?.getOrNull(1) as? Int ?: 0
                entityData.infoString(verbose, indent)
            }

            "propertiesToPersist" -> entityData.propertiesToPersist()

            // Kotlin property getter pattern: getXxx() -> property "xxx"
            else -> {
                if (methodName.startsWith("get") && methodName.length > 3 && args.isNullOrEmpty()) {
                    val propertyName = methodName.substring(3).replaceFirstChar { it.lowercase() }
                    properties[propertyName]
                } else if (methodName.startsWith("is") && methodName.length > 2 && args.isNullOrEmpty()) {
                    // Boolean property: isXxx() -> property "xxx" or "isXxx"
                    val propertyName = methodName.substring(2).replaceFirstChar { it.lowercase() }
                    properties[propertyName] ?: properties[methodName]
                } else {
                    // Try direct property lookup
                    properties[methodName]
                }
            }
        }
    }
}
