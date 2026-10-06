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
package com.embabel.common.ai.decision

import com.embabel.common.ai.classification.ClassificationResult
import org.jetbrains.annotations.ApiStatus
import tools.jackson.core.JacksonException
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper

/**
 * A caller-owned value read out of a decision response, kept together with the response it came
 * from. Build one with one of the [of] overloads, which read the response's answered values and
 * convert them to the target type with Jackson. The default mapper fails when a constructor
 * parameter of the target has no answered value, so a record component with no matching question is
 * an error. A mapper passed in keeps its own settings.
 *
 * For a spec with an `is_urgent` proposition and a `department` choice, a record whose component
 * names match the question names receives the answers:
 *
 * ```java
 * record SupportRoute(boolean is_urgent, String department) {}
 *
 * var route = DecisionProjection.of(response, SupportRoute.class).getValue();
 * // SupportRoute[is_urgent=true, department=billing]
 * ```
 *
 * @param T the caller's target type
 * @property value the projected value
 * @property response the response the value was read from, unchanged
 */
@ApiStatus.Experimental
class DecisionProjection<T : Any> private constructor(val value: T, val response: DecisionResponse) {

    companion object {

        /**
         * Keeps a caller-projected value together with its original response. The caller is
         * responsible for validating the response and converting the value before calling this.
         */
        @JvmStatic
        fun <T : Any> ofValue(value: T, response: DecisionResponse): DecisionProjection<T> =
            DecisionProjection(value, response)

        private val defaultMapper: ObjectMapper by lazy {
            JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).build()
        }

        /**
         * Projects the response's answered values onto [type], using a default JSON mapper to do
         * the conversion.
         *
         * @throws DecisionProjectionException if an answer has no representable value, or the
         * answered values do not fit [type]
         */
        @JvmStatic
        fun <T : Any> of(response: DecisionResponse, type: Class<T>): DecisionProjection<T> =
            of(response, type, defaultMapper)

        /**
         * Projects the response's answered values onto [type], using [mapper] to do the conversion.
         *
         * @throws DecisionProjectionException if an answer has no representable value, or the
         * answered values do not fit [type]
         */
        @JvmStatic
        fun <T : Any> of(response: DecisionResponse, type: Class<T>, mapper: ObjectMapper): DecisionProjection<T> {
            val values = answeredValues(response)
            return DecisionProjection(convert(type.name) { mapper.convertValue(values, type) }, response)
        }

        /**
         * Projects the response's answered values onto [type], using a default JSON mapper to do
         * the conversion. Use this overload for a generic target type, such as a map.
         *
         * @throws DecisionProjectionException if an answer has no representable value, or the
         * answered values do not fit [type]
         */
        @JvmStatic
        fun <T : Any> of(response: DecisionResponse, type: TypeReference<T>): DecisionProjection<T> =
            of(response, type, defaultMapper)

        /**
         * Projects the response's answered values onto [type], using [mapper] to do the conversion.
         * Use this overload for a generic target type, such as a map.
         *
         * @throws DecisionProjectionException if an answer has no representable value, or the
         * answered values do not fit [type]
         */
        @JvmStatic
        fun <T : Any> of(
            response: DecisionResponse,
            type: TypeReference<T>,
            mapper: ObjectMapper,
        ): DecisionProjection<T> {
            val values = answeredValues(response)
            return DecisionProjection(convert(type.type.typeName) { mapper.convertValue(values, type) }, response)
        }

        /**
         * Reduces every answer in the response to one plain value keyed by question name: a
         * proposition's Boolean answer, a choice's selected category id, or a rating's selected
         * level id. An answer whose outcome carries no such value is left out and reported instead,
         * including a rating that was answered with only a score or a distribution and no
         * selected level.
         *
         * @throws DecisionProjectionException naming every answer that has no representable value,
         * together with its outcome
         */
        @JvmStatic
        fun answeredValues(response: DecisionResponse): Map<String, Any> = answeredValues(response.answers)

        /**
         * Reduces selected answers with the same strict rules as [answeredValues]. Adapters may
         * preserve richer outcomes for other fields; an empty list produces an empty map.
         */
        @JvmStatic
        fun answeredValues(answers: List<DecisionAnswer>): Map<String, Any> {
            val values = LinkedHashMap<String, Any>()
            val unresolved = LinkedHashMap<String, String>()
            for (answer in answers) {
                val value = projectedValue(answer)
                if (value != null) {
                    values[answer.name] = value
                } else {
                    unresolved[answer.name] = outcomeStatus(answer)
                }
            }
            if (unresolved.isNotEmpty()) {
                throw DecisionProjectionException(
                    "Cannot project every answer to a value. Not representable: " +
                        unresolved.entries.joinToString { (name, status) -> "'$name' ($status)" },
                    unresolved.keys.toList(),
                    null,
                )
            }
            return java.util.Collections.unmodifiableMap(values)
        }

        /**
         * Reduces one answer to its plain value.
         *
         * @param answer the answer to reduce
         * @return the plain value, or null when its outcome carries none
         */
        private fun projectedValue(answer: DecisionAnswer): Any? = when (answer) {
            is DecisionAnswer.Proposition -> (answer.outcome as? PropositionResult.Answered)?.answer
            is DecisionAnswer.Choice -> (answer.outcome as? ClassificationResult.Selected)?.categoryId
            is DecisionAnswer.Rating -> (answer.outcome as? RatingResult.Answered)?.selectedLevelId
        }

        /**
         * Builds a short label for an outcome with no plain value, used in the exception message.
         *
         * @param answer the answer whose outcome has no plain value
         * @return the label
         */
        private fun outcomeStatus(answer: DecisionAnswer): String = when (answer) {
            is DecisionAnswer.Proposition -> when (answer.outcome) {
                is PropositionResult.Answered -> "answered"
                is PropositionResult.Inconclusive -> "inconclusive"
                is PropositionResult.Failure -> "failure"
            }

            is DecisionAnswer.Choice -> when (answer.outcome) {
                is ClassificationResult.Selected -> "selected"
                is ClassificationResult.NoMatch -> "no_match"
                is ClassificationResult.Inconclusive -> "inconclusive"
                is ClassificationResult.Failure -> "failure"
            }

            is DecisionAnswer.Rating -> when (val outcome = answer.outcome) {
                is RatingResult.Answered ->
                    if (outcome.selectedLevelId == null) "answered without a selected level" else "answered"
                is RatingResult.Inconclusive -> "inconclusive"
                is RatingResult.Failure -> "failure"
            }
        }

        /**
         * Runs a Jackson conversion, turning a mapping failure into a DecisionProjectionException.
         *
         * @param typeName the target type's name, used in the error message
         * @param block the conversion to run
         * @return the converted value
         */
        private fun <T> convert(typeName: String, block: () -> T): T =
            try {
                block()
            } catch (e: JacksonException) {
                throw DecisionProjectionException("Cannot map the answered values to $typeName", emptyList(), e)
            }
    }

    override fun toString(): String = "DecisionProjection(value=$value)"
}

/**
 * Thrown when a decision response's answers cannot be projected to a caller's target type: either
 * one or more answers carry no representable value, or the values that were collected do not fit
 * the target type.
 *
 * @property questions the names of the answers that had no representable value. It is empty when
 * the failure happened instead while mapping the collected values to the target type.
 */
@ApiStatus.Experimental
class DecisionProjectionException @JvmOverloads constructor(
    message: String,
    questions: List<String>,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    val questions: List<String> = java.util.List.copyOf(questions)
}
