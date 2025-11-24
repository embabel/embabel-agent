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
            return cpa != null
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
                clazz.name
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
            return clazz.declaredFields.mapNotNull { field ->
                // Check if it's a collection with a generic type parameter
                if (Collection::class.java.isAssignableFrom(field.type) && field.genericType is java.lang.reflect.ParameterizedType) {
                    val parameterizedType = field.genericType as java.lang.reflect.ParameterizedType
                    val typeArg = parameterizedType.actualTypeArguments.firstOrNull() as? Class<*>
                    if (typeArg != null && shouldNestAsEntity(typeArg)) {
                        val cardinality = when {
                            Set::class.java.isAssignableFrom(field.type) -> Cardinality.SET
                            else -> Cardinality.LIST
                        }
                        return@mapNotNull DomainTypePropertyDefinition(
                            name = field.name,
                            type = JvmType(typeArg),
                            cardinality = cardinality,
                        )
                    }
                    // Collection of scalars - return simple property
                    return@mapNotNull SimplePropertyDefinition(
                        name = field.name,
                        type = field.type.simpleName,
                    )
                } else if (shouldNestAsEntity(field.type)) {
                    DomainTypePropertyDefinition(
                        name = field.name,
                        type = JvmType(field.type),
                    )
                } else {
                    SimplePropertyDefinition(
                        name = field.name,
                        type = field.type.simpleName,
                    )
                }
            }
        }

    private fun shouldNestAsEntity(type: Class<*>): Boolean {
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
