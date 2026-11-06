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
 * The receiver passed to a [decisionSpec] block. Each method here adds one question to the spec
 * being built, through the matching [DecisionSpec.Builder] method, so a spec built with this DSL
 * validates exactly like one built with the builder or with `Questions.named(...)`.
 *
 * Get one only as the receiver of a [decisionSpec] block. The [DecisionSpecDsl] marker stops a
 * nested question block from reaching back into the enclosing spec scope by accident.
 */
@ApiStatus.Experimental
@DecisionSpecDsl
class DecisionSpecScope private constructor(private val builder: DecisionSpec.Builder) {

    /**
     * Declares a proposition question. The block runs once, on a fresh
     * [PropositionQuestionSpec.Builder] that already carries the name, and must at least call
     * `asking(...)`. It does not run when the name is blank or already used.
     *
     * @param name the question name, which must not be blank or used by an earlier question
     * @param block sets the question's definition
     * @throws IllegalArgumentException if the name is blank or already used, or the definition is invalid
     */
    @JvmSynthetic
    fun proposition(name: String, block: PropositionQuestionSpec.Builder.() -> Unit) {
        builder.proposition(name) { it.block() }
    }

    /**
     * Declares a choice question. The block runs once, on a fresh [ChoiceQuestionSpec.Builder]
     * that already carries the name, and must call `asking(...)` and add at least one option. It
     * does not run when the name is blank or already used.
     *
     * @param name the question name, which must not be blank or used by an earlier question
     * @param block sets the question's definition
     * @throws IllegalArgumentException if the name is blank or already used, or the definition is invalid
     */
    @JvmSynthetic
    fun choice(name: String, block: ChoiceQuestionSpec.Builder.() -> Unit) {
        builder.choice(name) { it.block() }
    }

    /**
     * Declares a rating question. The block runs once, on a fresh [RatingQuestionSpec.Builder]
     * that already carries the name, and must call `asking(...)` and add at least two levels,
     * lowest first. It does not run when the name is blank or already used.
     *
     * @param name the question name, which must not be blank or used by an earlier question
     * @param block sets the question's definition
     * @throws IllegalArgumentException if the name is blank or already used, or the definition is invalid
     */
    @JvmSynthetic
    fun rating(name: String, block: RatingQuestionSpec.Builder.() -> Unit) {
        builder.rating(name) { it.block() }
    }

    /**
     * Adds a question that was built earlier, for example with `Questions.named(...)`.
     *
     * @param question the question to add, whose name must not be used by an earlier question
     * @throws IllegalArgumentException if the name is already used
     */
    @JvmSynthetic
    fun question(question: Question<*>) {
        builder.question(question)
    }

    internal companion object {
        // Used by decisionSpec. Hidden from Java so a scope can only come from there.
        @JvmSynthetic
        internal fun create(builder: DecisionSpec.Builder): DecisionSpecScope = DecisionSpecScope(builder)
    }
}

/**
 * Builds a decision spec with a Kotlin receiver DSL. The block declares questions on
 * [DecisionSpecScope] and runs once. The spec is built once, after the block returns, so later
 * calls on a leaked question builder cannot change it.
 *
 * ```kotlin
 * val triage = decisionSpec {
 *     proposition("is_urgent") {
 *         asking("Does this convey urgency?")
 *     }
 *     choice("department") {
 *         asking("Which team should handle this?")
 *         option("billing", "Payments, invoicing, refunds")
 *         option("technical", "Bugs, outages, integrations")
 *     }
 * }
 * ```
 *
 * @param block declares the spec's questions
 * @return the built spec
 * @throws IllegalArgumentException if no question is declared, two questions share a name, or a
 * declared question's definition is invalid
 */
@ApiStatus.Experimental
@JvmSynthetic
fun decisionSpec(block: DecisionSpecScope.() -> Unit): DecisionSpec {
    val builder = DecisionSpec.builder()
    DecisionSpecScope.create(builder).block()
    return builder.build()
}
