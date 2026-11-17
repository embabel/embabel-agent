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
import java.util.Collections
import java.util.Objects

/**
 * Marks the receiver of the Kotlin block that builds a [DecisionCall], so code inside the block
 * can't reach an outer builder by accident.
 */
@ApiStatus.Experimental
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
annotation class DecisionCallDsl

/**
 * One request to a decision service: what to reason over, the questions to answer, and any
 * options for this call alone, such as who the end user is.
 *
 * A call lives only in memory for the length of one invocation. Use a [DecisionRequest] when you
 * need a document you can store or send. Calls never change: every `with` method hands back a new
 * call and leaves this one as it was.
 *
 * Option values are kept out of [toString] because they can identify a person, and so is the input.
 *
 * From Kotlin, build a call with a block:
 *
 * ```kotlin
 * val call = DecisionCall(input, spec) {
 *     option(DecisionOptions.TIMEOUT, Duration.ofSeconds(5))
 * }
 * ```
 *
 * From Java, chain the `with` methods:
 *
 * ```java
 * DecisionCall call = DecisionCall.of(input, spec)
 *     .withOption(DecisionOptions.TIMEOUT, Duration.ofSeconds(5));
 * ```
 */
@ApiStatus.Experimental
class DecisionCall private constructor(
    /** The questions to answer. */
    val spec: DecisionSpec,
    /** What the decision reasons over. */
    val input: DecisionInput,
    private val options: Map<DecisionOption<*>, Any>,
    /**
     * Whether the caller asked for the input to reach the provider as JSON. Only a call with a
     * [JsonDecisionInput] can ask for this.
     */
    val nativeJsonRequired: Boolean,
) {

    init {
        require(!nativeJsonRequired || input is JsonDecisionInput) {
            "Native JSON can only be required for a JSON input, but the input kind is '${input.kind}'"
        }
    }

    /** The keys of the options set on this call, in the order they were first set. The set cannot be modified. */
    val optionKeys: Set<DecisionOption<*>> = Collections.unmodifiableSet(LinkedHashSet(options.keys))

    /**
     * Returns the value set for an option on this call.
     *
     * @param key the option to read
     * @return the value, or null when the option isn't set
     */
    fun <T : Any> option(key: DecisionOption<T>): T? = options[key]?.let { key.type.cast(it) }

    /**
     * Returns a copy of this call with one option set. Setting an option that's already set
     * replaces its value.
     *
     * @param key the option to set
     * @param value the value to give it
     * @return the new call
     * @throws IllegalArgumentException if the value isn't an instance of the key's type
     */
    fun <T : Any> withOption(key: DecisionOption<T>, value: T): DecisionCall {
        checkValue(key, value)
        val copy = LinkedHashMap(options)
        copy[key] = value
        return DecisionCall(spec, input, copy, nativeJsonRequired)
    }

    /**
     * Returns a copy of this call that reasons over a different input.
     *
     * @param input the new input
     * @return the new call
     * @throws IllegalArgumentException if this call requires native JSON and the new input isn't JSON
     */
    fun withInput(input: DecisionInput): DecisionCall = DecisionCall(spec, input, options, nativeJsonRequired)

    /**
     * Returns a copy of this call that asks for its JSON input to reach the provider as JSON.
     *
     * @return the new call
     * @throws IllegalArgumentException if the input isn't a [JsonDecisionInput]
     */
    fun requiringNativeJson(): DecisionCall = DecisionCall(spec, input, options, true)

    /** Two calls are equal when their spec, input, options and native JSON flag are all equal. */
    override fun equals(other: Any?): Boolean =
        this === other || other is DecisionCall &&
            spec == other.spec &&
            input == other.input &&
            options == other.options &&
            nativeJsonRequired == other.nativeJsonRequired

    override fun hashCode(): Int = Objects.hash(spec, input, options, nativeJsonRequired)

    /**
     * Shows the input kind, the question names and the option ids. Option values and the input
     * itself are left out because they can hold private details.
     */
    override fun toString(): String =
        "DecisionCall(input=${input.kind}, questions=${spec.questions.map { it.name }}, " +
            "options=${optionKeys.map { it.id }}, nativeJsonRequired=$nativeJsonRequired)"

    /**
     * The receiver of the Kotlin block that builds a call. Each setting in the block works like
     * the matching `with` method.
     */
    @DecisionCallDsl
    class Scope internal constructor() {

        private val options = LinkedHashMap<DecisionOption<*>, Any>()

        private var nativeJson = false

        /**
         * Sets an option on the call. Setting the same option again replaces its value.
         *
         * @param key the option to set
         * @param value the value to give it
         * @throws IllegalArgumentException if the value isn't an instance of the key's type
         */
        fun <T : Any> option(key: DecisionOption<T>, value: T) {
            checkValue(key, value)
            options[key] = value
        }

        /**
         * Asks for the call's JSON input to reach the provider as JSON. Building the call fails
         * with an [IllegalArgumentException] if the input isn't a [JsonDecisionInput].
         */
        fun requireNativeJson() {
            nativeJson = true
        }

        internal fun build(spec: DecisionSpec, input: DecisionInput): DecisionCall =
            DecisionCall(spec, input, LinkedHashMap(options), nativeJson)
    }

    /**
     * Creates decision calls.
     */
    companion object {

        private fun checkValue(key: DecisionOption<*>, value: Any) {
            require(key.type.isInstance(value)) {
                "Option '${key.id}' needs a value of type ${key.type.name}, but got ${value.javaClass.name}"
            }
        }

        /**
         * Returns a call that answers a request's questions over its input, with no options set.
         *
         * A request built from a structured input is read as its text form until structured
         * requests are available on this branch.
         *
         * @param request the request to answer
         * @return the call, whose input is a [TextDecisionInput] holding the request's input
         */
        @JvmStatic
        fun of(request: DecisionRequest): DecisionCall = of(TextDecisionInput(request.input), request.spec)

        /**
         * Returns a call that answers the given questions over the given input, with no options set.
         *
         * @param input what the decision reasons over
         * @param spec the questions to answer
         * @return the call
         */
        @JvmStatic
        fun of(input: DecisionInput, spec: DecisionSpec): DecisionCall = DecisionCall(spec, input, emptyMap(), false)

        /**
         * Builds a call from a request with a Kotlin block that sets its options.
         *
         * @param request the request to answer, whose input is read as text
         * @param block sets options on the call
         * @return the call
         * @throws IllegalArgumentException if an option value has the wrong type, or native JSON is
         * required for a text input
         */
        @JvmSynthetic
        operator fun invoke(request: DecisionRequest, block: Scope.() -> Unit): DecisionCall =
            invoke(TextDecisionInput(request.input), request.spec, block)

        /**
         * Builds a call with a Kotlin block that sets its options.
         *
         * @param input what the decision reasons over
         * @param spec the questions to answer
         * @param block sets options on the call
         * @return the call
         * @throws IllegalArgumentException if an option value has the wrong type, or native JSON is
         * required for an input that isn't JSON
         */
        @JvmSynthetic
        operator fun invoke(input: DecisionInput, spec: DecisionSpec, block: Scope.() -> Unit): DecisionCall =
            Scope().apply(block).build(spec, input)
    }
}
