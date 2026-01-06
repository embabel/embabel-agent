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

/**
 * A key-value pair for semantic metadata.
 * Used within [@Semantics] to define semantic properties of a field.
 *
 * Kotlin example:
 * ```kotlin
 * @Semantics([
 *     With("predicate", "works at"),
 *     With("inverse", "employs")
 * ])
 * val worksAt: Company
 * ```
 *
 * Java example (on class field):
 * ```java
 * @Semantics({
 *     @With(key = "predicate", value = "works at"),
 *     @With(key = "inverse", value = "employs")
 * })
 * private Company worksAt;
 * ```
 *
 * Java example (on interface getter):
 * ```java
 * public interface Employee {
 *     @Semantics({
 *         @With(key = "predicate", value = "works at"),
 *         @With(key = "inverse", value = "employs")
 *     })
 *     Company getWorksAt();
 * }
 * ```
 */
@Target()
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class With(
    /**
     * The key for this semantic property.
     */
    val key: String,

    /**
     * The value for this semantic property.
     */
    val value: String,
)

/**
 * Defines semantic metadata for a property.
 * This annotation allows attaching arbitrary key-value metadata to properties,
 * which can be used for semantic processing such as proposition extraction,
 * relationship mapping, and natural language generation.
 *
 * Common semantic properties include:
 * - `predicate`: The natural language predicate for the relationship (e.g., "works at")
 * - `inverse`: The inverse predicate for bidirectional reasoning (e.g., "employs")
 * - `aliases`: Alternative phrasings that map to this relationship
 *
 * Kotlin example:
 * ```kotlin
 * data class Person(
 *     val name: String,
 *
 *     @Semantics([
 *         With("predicate", "works at"),
 *         With("inverse", "employs"),
 *         With("aliases", "is employed by, works for")
 *     ])
 *     val worksAt: Company
 * )
 * ```
 *
 * Java example (class with field):
 * ```java
 * public class Person {
 *     private String name;
 *
 *     @Semantics({
 *         @With(key = "predicate", value = "works at"),
 *         @With(key = "inverse", value = "employs"),
 *         @With(key = "aliases", value = "is employed by, works for")
 *     })
 *     private Company worksAt;
 * }
 * ```
 *
 * Java example (interface with getter):
 * ```java
 * public interface HasEmployment {
 *     @Semantics({
 *         @With(key = "predicate", value = "works at"),
 *         @With(key = "inverse", value = "employs")
 *     })
 *     Company getWorksAt();
 * }
 * ```
 *
 * The metadata is accessible via [PropertyDefinition.metadata] as a Map<String, String>.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class Semantics(
    /**
     * The semantic properties for this field.
     */
    val value: Array<With> = [],
)
