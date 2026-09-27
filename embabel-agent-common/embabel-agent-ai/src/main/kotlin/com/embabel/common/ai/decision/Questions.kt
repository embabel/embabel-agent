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

import org.jetbrains.annotations.ApiStatus

/**
 * The starting point for declaring a question on its own. A question declared this way can be
 * kept in a field, added to several specs, and used to look up its typed answer in a response.
 *
 * ```java
 * var department = Questions.named("department")
 *     .choice("Which team should handle this?")
 *     .option("billing", "Payments, invoicing, refunds")
 *     .option("technical", "Bugs, outages, integrations")
 *     .build();
 * ```
 */
@ApiStatus.Experimental
object Questions {

    /**
     * Starts a question with the given name.
     *
     * @param name the question name, which must not be blank
     * @return the step that picks the question's kind
     * @throws IllegalArgumentException if the name is blank
     */
    @JvmStatic
    fun named(name: String): Named = Named.create(name)

    /**
     * A question name waiting for its kind. Each method starts a builder of that kind with the
     * name and instructions already set.
     */
    @ApiStatus.Experimental
    class Named private constructor(private val name: String) {

        /**
         * Starts a proposition question.
         *
         * @param instructions the text that tells the model what to decide
         * @return a builder holding the name and instructions
         */
        fun proposition(instructions: String): PropositionQuestionSpec.Builder =
            PropositionQuestionSpec.Builder.create(name).asking(instructions)

        /**
         * Starts a choice question. Add at least one option before building it.
         *
         * @param instructions the text that tells the model what to decide
         * @return a builder holding the name and instructions
         */
        fun choice(instructions: String): ChoiceQuestionSpec.Builder =
            ChoiceQuestionSpec.Builder.create(name).asking(instructions)

        /**
         * Starts a rating question. Add at least two levels, lowest first, before building it.
         *
         * @param instructions the text that tells the model what to decide
         * @return a builder holding the name and instructions
         */
        fun rating(instructions: String): RatingQuestionSpec.Builder =
            RatingQuestionSpec.Builder.create(name).asking(instructions)

        internal companion object {
            // Hidden from Java so a Named can only come from Questions.named.
            @JvmSynthetic
            internal fun create(name: String): Named {
                require(name.isNotBlank()) { "Question name must not be blank" }
                return Named(name)
            }
        }
    }
}
