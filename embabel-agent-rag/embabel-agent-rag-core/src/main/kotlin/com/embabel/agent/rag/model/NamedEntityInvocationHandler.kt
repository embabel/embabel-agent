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

import com.embabel.agent.rag.service.RetrievableIdentifier
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType

/**
 * InvocationHandler that backs a [NamedEntity] instance with a property map.
 *
 * Supports lazy relationship navigation when a [RelationshipNavigator] is provided
 * and methods are annotated with [@Semantics][Semantics].
 *
 * @param properties the entity's properties (id, name, description, etc.)
 * @param metadata the entity's metadata
 * @param labels the entity's labels
 * @param entityData the backing entity data
 * @param navigator optional navigator for relationship traversal
 * @param interfaces the interfaces this proxy implements (for determining return types)
 */
internal class NamedEntityInvocationHandler(
    private val properties: Map<String, Any?>,
    private val metadata: Map<String, Any?>,
    private val labels: Set<String>,
    private val entityData: NamedEntityData,
    private val navigator: RelationshipNavigator? = null,
    private val interfaces: Array<out Class<*>> = emptyArray(),
) : InvocationHandler {

    // Cache for lazy-loaded relationships
    private val relationshipCache = mutableMapOf<String, Any?>()

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

            // Check for relationship navigation, property getter, or default method
            else -> handlePropertyOrRelationship(proxy, method, methodName, args)
        }
    }

    private fun handlePropertyOrRelationship(
        proxy: Any,
        method: Method,
        methodName: String,
        args: Array<out Any>?,
    ): Any? {
        // Check for @Relationship annotation
        val relationship = findRelationshipAnnotation(method)
        if (relationship != null && navigator != null) {
            return handleRelationship(method, relationship)
        }

        // Standard property getter handling
        if (methodName.startsWith("get") && methodName.length > 3 && args.isNullOrEmpty()) {
            val propertyName = methodName.substring(3).replaceFirstChar { it.lowercase() }
            return properties[propertyName]
        } else if (methodName.startsWith("is") && methodName.length > 2 && args.isNullOrEmpty()) {
            // Boolean property: isXxx() -> property "xxx" or "isXxx"
            val propertyName = methodName.substring(2).replaceFirstChar { it.lowercase() }
            return properties[propertyName] ?: properties[methodName]
        }

        // Check for default interface method (business logic methods)
        if (method.isDefault) {
            return invokeDefaultMethod(proxy, method, args)
        }

        // Try direct property lookup
        return properties[methodName]
    }

    /**
     * Invoke a default interface method using MethodHandles.
     * This allows business logic methods defined in interfaces to work correctly.
     */
    private fun invokeDefaultMethod(proxy: Any, method: Method, args: Array<out Any>?): Any? {
        val declaringClass = method.declaringClass
        val lookup = MethodHandles.privateLookupIn(declaringClass, MethodHandles.lookup())
        val methodHandle = lookup.findSpecial(
            declaringClass,
            method.name,
            MethodType.methodType(method.returnType, method.parameterTypes),
            declaringClass
        )
        return if (args.isNullOrEmpty()) {
            methodHandle.bindTo(proxy).invokeWithArguments()
        } else {
            methodHandle.bindTo(proxy).invokeWithArguments(*args)
        }
    }

    /**
     * Find the @Relationship annotation on this method, checking all implemented interfaces.
     */
    private fun findRelationshipAnnotation(method: Method): Relationship? {
        // Check method directly
        method.getAnnotation(Relationship::class.java)?.let { return it }

        // Check interfaces for the same method signature
        for (iface in interfaces) {
            try {
                val ifaceMethod = iface.getMethod(method.name, *method.parameterTypes)
                ifaceMethod.getAnnotation(Relationship::class.java)?.let { return it }
            } catch (_: NoSuchMethodException) {
                // Method not found in this interface, continue
            }
        }
        return null
    }

    private fun handleRelationship(method: Method, relationship: Relationship): Any? {
        // Derive relationship name if not specified
        val relationshipName = relationship.name.ifEmpty { deriveRelationshipName(method.name) }
        val cacheKey = "$relationshipName:${relationship.direction}"

        // Return cached value if available
        relationshipCache[cacheKey]?.let { return it }

        val entityId = properties["id"] as? String ?: return null
        val entityType = labels.firstOrNull() ?: "Entity"
        val source = RetrievableIdentifier(id = entityId, type = entityType)
        val relatedEntities = navigator!!.findRelated(
            source,
            relationshipName,
            relationship.direction
        )

        val result = hydrateRelationshipResult(method, relatedEntities)
        relationshipCache[cacheKey] = result
        return result
    }

    /**
     * Hydrate relationship results to the appropriate return type.
     * Handles: single entity (T?), collection (List<T>), etc.
     */
    private fun hydrateRelationshipResult(method: Method, entities: List<NamedEntityData>): Any? {
        val returnType = method.returnType

        // Determine target entity type
        val targetType = extractTargetType(method)

        return when {
            // Collection type (List, Set, Collection, etc.)
            Collection::class.java.isAssignableFrom(returnType) -> {
                entities.mapNotNull { it.toInstanceOrNull(targetType) }
            }
            // Single entity (nullable)
            else -> {
                entities.firstOrNull()?.toInstanceOrNull(targetType)
            }
        }
    }

    /**
     * Extract the target entity type from the method return type.
     * For List<Person>, extracts Person.
     * For Company?, extracts Company.
     */
    private fun extractTargetType(method: Method): Class<out NamedEntity>? {
        val returnType = method.returnType

        // For collections, get the generic type parameter
        if (Collection::class.java.isAssignableFrom(returnType)) {
            val genericType = method.genericReturnType
            if (genericType is ParameterizedType) {
                val typeArg = genericType.actualTypeArguments.firstOrNull()
                if (typeArg is Class<*> && NamedEntity::class.java.isAssignableFrom(typeArg)) {
                    @Suppress("UNCHECKED_CAST")
                    return typeArg as Class<out NamedEntity>
                }
            }
            return null
        }

        // For single types
        if (NamedEntity::class.java.isAssignableFrom(returnType)) {
            @Suppress("UNCHECKED_CAST")
            return returnType as Class<out NamedEntity>
        }
        return null
    }

    private fun NamedEntityData.toInstanceOrNull(targetType: Class<out NamedEntity>?): NamedEntity? {
        if (targetType == null) return null
        return try {
            toInstance(targetType)
        } catch (_: Exception) {
            null
        }
    }
}
