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
package com.embabel.agent.core

import com.embabel.agent.api.common.SomeOf
import com.embabel.common.util.indentLines
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory

/**
 * Typed backed by a JVM object
 * It's good practice to annotate classes with @JsonClassDescription for better descriptions:
 * otherwise, only the simple class name will be used.
 * Use the @CreationPermitted annotation to indicate whether new instances of this type can be created.
 * Default is true.
 */
data class JvmType @JsonCreator constructor(
    @param:JsonProperty("className")
    val className: String,
) : DomainType {

    constructor(clazz: Class<*>) : this(clazz.name)

    @get:JsonIgnore
    override val creationPermitted: Boolean
        get() {
            val cpa = clazz.getAnnotation(CreationPermitted::class.java)
            return cpa?.value ?: true
        }

    @get:JsonIgnore
    override val parents: List<JvmType>
        get() {
            val superclass = clazz.superclass
            val parentList = mutableListOf<JvmType>()
            if (superclass != null && superclass != Object::class.java) {
                parentList.add(JvmType(superclass))
            }
            clazz.interfaces.forEach { parentList.add(JvmType(it)) }
            return parentList
        }

    @get:JsonIgnore
    val clazz: Class<*> by lazy {
        if (className == "void") {
            Void.TYPE
        } else
            Class.forName(className)
    }

    @get:JsonIgnore
    override val name: String
        get() = className

    @get:JsonIgnore
    override val ownLabel: String
        get() = clazz.simpleName

    @get:JsonIgnore
    override val description: String
        get() {
            val ann = clazz.getAnnotation(JsonClassDescription::class.java)
            return if (ann != null) {
                "${clazz.simpleName}: ${ann.value}"
            } else {
                clazz.simpleName
            }
        }

    override fun isAssignableFrom(other: Class<*>): Boolean =
        clazz.isAssignableFrom(other)

    override fun isAssignableFrom(other: DomainType): Boolean =
        when (other) {
            is JvmType -> clazz.isAssignableFrom(other.clazz)
            is DynamicType -> false
        }

    override fun isAssignableTo(other: Class<*>): Boolean =
        other.isAssignableFrom(clazz)

    override fun isAssignableTo(other: DomainType): Boolean =
        when (other) {
            is JvmType -> other.clazz.isAssignableFrom(clazz)
            is DynamicType -> false
        }

    override fun children(additionalBasePackages: Collection<String>): Collection<JvmType> {
        val basePackagesToUse = additionalBasePackages.ifEmpty {
            listOf(clazz.packageName)
        }
        val scanner = org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(org.springframework.core.type.filter.AssignableTypeFilter(clazz))

        val result = mutableListOf<JvmType>()

        for (packageName in basePackagesToUse) {
            try {
                val candidateComponents = scanner.findCandidateComponents(packageName)
                for (beanDef in candidateComponents) {
                    try {
                        val className = beanDef.beanClassName
                        if (className != null && className != clazz.name) {
                            val candidateClass = Class.forName(className)
                            // Exclude the class itself and ensure it's actually assignable
                            if (candidateClass != clazz && clazz.isAssignableFrom(candidateClass)) {
                                result.add(JvmType(candidateClass))
                            }
                        }
                    } catch (e: ClassNotFoundException) {
                        // Skip classes that can't be loaded
                        JvmType.logger.debug("Could not load class: ${beanDef.beanClassName}", e)
                    } catch (e: Exception) {
                        // Skip classes that cause other issues
                        JvmType.logger.debug("Error processing class: ${beanDef.beanClassName}", e)
                    }
                }
            } catch (e: Exception) {
                // Skip packages that can't be scanned
                JvmType.logger.debug("Could not scan package: $packageName", e)
            }
        }

        return result.toSet()
    }

    @get:JsonIgnore
    override val ownProperties: List<PropertyDefinition>
        get() {
            val fieldProperties = clazz.declaredFields
                .filter { field -> !shouldExcludeField(field) }
                .mapNotNull { field -> propertyFromField(field) }

            // Also extract properties from getter methods (important for interfaces)
            val methodProperties = clazz.declaredMethods
                .filter { method -> isGetter(method) && !shouldExcludeMethod(method) }
                .mapNotNull { method -> propertyFromMethod(method) }

            // Combine, with field properties taking precedence (by name)
            val propertiesByName = mutableMapOf<String, PropertyDefinition>()
            methodProperties.forEach { propertiesByName[it.name] = it }
            fieldProperties.forEach { propertiesByName[it.name] = it }

            return propertiesByName.values.toList()
        }

    private fun propertyFromField(field: java.lang.reflect.Field): PropertyDefinition? {
        val metadata = extractSemantics(field)

        // Check if it's a collection with a generic type parameter
        if (Collection::class.java.isAssignableFrom(field.type) && field.genericType is java.lang.reflect.ParameterizedType) {
            val parameterizedType = field.genericType as java.lang.reflect.ParameterizedType
            val typeArg = parameterizedType.actualTypeArguments.firstOrNull() as? Class<*>
            if (typeArg != null && shouldNestAsEntity(typeArg)) {
                val cardinality = when {
                    Set::class.java.isAssignableFrom(field.type) -> Cardinality.SET
                    else -> Cardinality.LIST
                }
                return DomainTypePropertyDefinition(
                    name = field.name,
                    type = JvmType(typeArg),
                    cardinality = cardinality,
                    metadata = metadata,
                )
            }
            // Collection of scalars - return simple property
            return ValuePropertyDefinition(
                name = field.name,
                type = field.type.simpleName,
                metadata = metadata,
            )
        } else if (shouldNestAsEntity(field.type)) {
            return DomainTypePropertyDefinition(
                name = field.name,
                type = JvmType(field.type),
                metadata = metadata,
            )
        } else {
            return ValuePropertyDefinition(
                name = field.name,
                type = field.type.simpleName,
                metadata = metadata,
            )
        }
    }

    private fun propertyFromMethod(method: java.lang.reflect.Method): PropertyDefinition? {
        val propertyName = extractPropertyName(method) ?: return null
        val returnType = method.returnType
        val genericReturnType = method.genericReturnType

        // Check for @Relationship annotation - use reflection to avoid hard dependency
        val relationshipAnnotation = method.annotations.find {
            it.annotationClass.qualifiedName == "com.embabel.agent.rag.model.Relationship"
        }
        val relationshipName = relationshipAnnotation?.let { ann ->
            try {
                val nameMethod = ann.annotationClass.java.getMethod("name")
                val name = nameMethod.invoke(ann) as? String
                name?.takeIf { it.isNotEmpty() } ?: deriveRelationshipName(method.name)
            } catch (e: Exception) {
                deriveRelationshipName(method.name)
            }
        }

        // Check if it's a collection with a generic type parameter
        if (Collection::class.java.isAssignableFrom(returnType) && genericReturnType is java.lang.reflect.ParameterizedType) {
            val typeArg = genericReturnType.actualTypeArguments.firstOrNull() as? Class<*>
            if (typeArg != null && shouldNestAsEntity(typeArg)) {
                val cardinality = when {
                    Set::class.java.isAssignableFrom(returnType) -> Cardinality.SET
                    else -> Cardinality.LIST
                }
                return DomainTypePropertyDefinition(
                    name = relationshipName ?: propertyName,
                    type = JvmType(typeArg),
                    cardinality = cardinality,
                    description = relationshipName ?: propertyName,
                )
            }
            // Collection of scalars - return simple property
            return ValuePropertyDefinition(
                name = propertyName,
                type = returnType.simpleName,
            )
        } else if (shouldNestAsEntity(returnType)) {
            return DomainTypePropertyDefinition(
                name = relationshipName ?: propertyName,
                type = JvmType(returnType),
                description = relationshipName ?: propertyName,
            )
        } else {
            return ValuePropertyDefinition(
                name = propertyName,
                type = returnType.simpleName,
            )
        }
    }

    private fun isGetter(method: java.lang.reflect.Method): Boolean {
        if (method.parameterCount != 0) return false
        if (method.returnType == Void.TYPE) return false
        val name = method.name
        val isBooleanReturn = method.returnType == Boolean::class.javaPrimitiveType ||
            method.returnType == java.lang.Boolean::class.java
        return (name.startsWith("get") && name.length > 3) ||
            (name.startsWith("is") && name.length > 2 && isBooleanReturn)
    }

    private fun extractPropertyName(method: java.lang.reflect.Method): String? {
        val name = method.name
        return when {
            name.startsWith("get") && name.length > 3 ->
                name.substring(3).replaceFirstChar { it.lowercase() }
            name.startsWith("is") && name.length > 2 ->
                name.substring(2).replaceFirstChar { it.lowercase() }
            else -> null
        }
    }

    private fun shouldExcludeMethod(method: java.lang.reflect.Method): Boolean {
        // Exclude methods from Object and common interfaces
        if (method.declaringClass == Object::class.java) return true
        // Exclude bridge and synthetic methods
        if (method.isBridge || method.isSynthetic) return true
        // Exclude default methods that are just helpers (like lifespan() in Composer)
        if (method.isDefault && !isGetter(method)) return true
        return false
    }

    /**
     * Derives the default relationship name from a method name.
     * Converts getter method names to UPPER_SNAKE_CASE format.
     */
    private fun deriveRelationshipName(methodName: String): String {
        val propertyName = when {
            methodName.startsWith("get") && methodName.length > 3 ->
                methodName.substring(3)
            methodName.startsWith("is") && methodName.length > 2 ->
                methodName.substring(2)
            else -> methodName
        }
        return propertyName.replace(Regex("([a-z])([A-Z])")) { "${it.groupValues[1]}_${it.groupValues[2]}" }
            .uppercase()
    }

    /**
     * Extract semantic metadata from a field's @Semantics annotation.
     */
    private fun extractSemantics(field: java.lang.reflect.Field): Map<String, String> {
        val semantics = field.getAnnotation(Semantics::class.java) ?: return emptyMap()
        return semantics.value.associate { it.key to it.value }
    }

    /**
     * Check if a field should be excluded from properties.
     * Excludes:
     * - Static fields (not instance properties, includes Kotlin const vals)
     * - Companion object references (Kotlin companion objects)
     */
    private fun shouldExcludeField(field: java.lang.reflect.Field): Boolean {
        // Exclude static fields (includes const vals from companion objects)
        if (java.lang.reflect.Modifier.isStatic(field.modifiers)) {
            return true
        }
        // Exclude Companion object fields
        if (field.name == "Companion" && field.type.name.endsWith("\$Companion")) {
            return true
        }
        return false
    }

    private fun shouldNestAsEntity(type: Class<*>): Boolean {
        // Kotlin companion objects are not entities
        if (type.name.endsWith("\$Companion")) {
            return false
        }
        // Primitives and their wrappers are scalars
        if (type.isPrimitive || type == java.lang.Boolean::class.java ||
            type == java.lang.Byte::class.java || type == java.lang.Short::class.java ||
            type == java.lang.Integer::class.java || type == java.lang.Long::class.java ||
            type == java.lang.Float::class.java || type == java.lang.Double::class.java ||
            type == java.lang.Character::class.java
        ) {
            return false
        }
        // Common scalar types
        if (type == String::class.java || type == java.math.BigDecimal::class.java ||
            type == java.math.BigInteger::class.java || type == java.util.Date::class.java ||
            type == java.time.LocalDate::class.java || type == java.time.LocalDateTime::class.java ||
            type == java.time.Instant::class.java
        ) {
            return false
        }
        // Collections are not nested as entities (their element types might be)
        if (Collection::class.java.isAssignableFrom(type) || Map::class.java.isAssignableFrom(type)) {
            return false
        }
        // Everything else is considered an entity
        return true
    }

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        return """
                |class: ${clazz.name}
                |"""
            .trimMargin()
            .indentLines(indent)
    }

    companion object {

        private val logger = LoggerFactory.getLogger(JvmType::class.java)

        /**
         * May need to break up with SomeOf
         */
        fun fromClasses(
            classes: Collection<Class<*>>,
        ): Collection<JvmType> {
            return classes.flatMap {
                if (SomeOf::class.java.isAssignableFrom(it)) {
                    SomeOf.eligibleFields(it)
                        .map { field ->
                            JvmType(field.type)
                        }
                } else {
                    listOf(JvmType(it))
                }
            }.toSet()
        }
    }

}
