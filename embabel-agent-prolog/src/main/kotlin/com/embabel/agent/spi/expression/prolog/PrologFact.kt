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

/**
 * Marks a data class as convertible to Prolog facts.
 * When an object of this type is encountered, its fields will be automatically
 * converted to Prolog predicates.
 *
 * @param predicatePrefix Prefix for generated predicates. If empty, uses the class name in lowercase.
 *                       For example, with prefix "user", a field "role" becomes "user_role".
 * @param exclude Field names to exclude from fact generation.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class PrologFact(
    val predicatePrefix: String = "",
    val exclude: Array<String> = [],
)
