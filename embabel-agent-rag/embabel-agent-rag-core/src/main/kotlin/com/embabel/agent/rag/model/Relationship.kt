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

/**
 * Marks a getter method as navigating a relationship to another entity.
 *
 * When applied to a getter on a [NamedEntity] interface, the dynamic proxy
 * will use the repository to lazily load the related entity/entities.
 *
 * Example:
 * ```kotlin
 * interface Person : NamedEntity {
 *     val name: String
 *
 *     @Relationship  // Defaults to "HAS_EMPLOYER"
 *     fun getEmployer(): Company?
 *
 *     @Relationship("OWNS")  // Explicit name
 *     fun getPets(): List<Pet>
 *
 *     @Relationship(direction = RelationshipDirection.INCOMING)  // "HAS_DIRECT_REPORTS"
 *     fun getDirectReports(): List<Person>
 * }
 * ```
 *
 * @property name The relationship type/name (e.g., "EMPLOYED_BY", "OWNS").
 *                If empty, defaults to `HAS_` + property name in UPPER_SNAKE_CASE.
 *                For example, `getEmployer()` -> "HAS_EMPLOYER", `getPets()` -> "HAS_PETS".
 * @property direction The direction of relationship traversal. Defaults to [RelationshipDirection.OUTGOING].
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Relationship(
    val name: String = "",
    val direction: RelationshipDirection = RelationshipDirection.OUTGOING,
)

/**
 * Direction of relationship traversal.
 */
enum class RelationshipDirection {
    /** Traverse outgoing relationships (this entity → target entity) */
    OUTGOING,
    /** Traverse incoming relationships (target entity → this entity) */
    INCOMING,
    /** Traverse both directions */
    BOTH
}

/**
 * Derives the default relationship name from a method name.
 *
 * Converts getter method names to HAS_UPPER_SNAKE_CASE format:
 * - `getEmployer` -> "HAS_EMPLOYER"
 * - `getPets` -> "HAS_PETS"
 * - `getDirectReports` -> "HAS_DIRECT_REPORTS"
 */
fun deriveRelationshipName(methodName: String): String {
    val propertyName = when {
        methodName.startsWith("get") && methodName.length > 3 ->
            methodName.substring(3)
        methodName.startsWith("is") && methodName.length > 2 ->
            methodName.substring(2)
        else -> methodName
    }
    return "HAS_" + propertyName.toUpperSnakeCase()
}

/**
 * Converts a camelCase or PascalCase string to UPPER_SNAKE_CASE.
 */
private fun String.toUpperSnakeCase(): String {
    return this.replace(Regex("([a-z])([A-Z])")) { "${it.groupValues[1]}_${it.groupValues[2]}" }
        .uppercase()
}
