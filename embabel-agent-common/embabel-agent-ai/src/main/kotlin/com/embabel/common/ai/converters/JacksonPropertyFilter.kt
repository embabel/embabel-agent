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
package com.embabel.common.ai.converters

import com.fasterxml.jackson.databind.introspect.AnnotatedField
import java.util.function.Predicate

sealed interface JacksonPropertyFilter {

    fun test(field: AnnotatedField): Boolean

    fun test(property: String): Boolean

    data class MatchesPropertyValue(
        val predicate: Predicate<String>
    ) : JacksonPropertyFilter {
        override fun test(field: AnnotatedField): Boolean {
            return true
        }

        override fun test(property: String): Boolean {
            return predicate.test(property)
        }
    }

    data class SkipAnnotation(
        val annotation: Class<out Annotation>
    ) : JacksonPropertyFilter {
        override fun test(field: AnnotatedField): Boolean {
            return !field.hasAnnotation(annotation)
        }

        override fun test(property: String): Boolean {
            return true
        }
    }

    data class Composite(
        val filters: List<JacksonPropertyFilter>
    ) : JacksonPropertyFilter {
        override fun test(field: AnnotatedField): Boolean {
            return filters.all { it.test(field) }
        }

        override fun test(property: String): Boolean {
            return filters.all { it.test(property) }
        }
    }

    infix fun and(other: Predicate<String>): JacksonPropertyFilter {
        return and(property(other))
    }

    infix fun and(other: JacksonPropertyFilter): JacksonPropertyFilter {
        return when {
            this is Composite && other is Composite ->
                Composite(this.filters + other.filters)

            this is Composite ->
                Composite(this.filters + other)

            other is Composite ->
                Composite(listOf(this) + other.filters)

            else ->
                Composite(listOf(this, other))
        }
    }

    companion object {

        fun allowAll(): JacksonPropertyFilter =
            MatchesPropertyValue { true }

        fun property(predicate: Predicate<String>) =
            MatchesPropertyValue(predicate)

        fun skip(annotation: Class<out Annotation>) =
            SkipAnnotation(annotation)
    }
}
