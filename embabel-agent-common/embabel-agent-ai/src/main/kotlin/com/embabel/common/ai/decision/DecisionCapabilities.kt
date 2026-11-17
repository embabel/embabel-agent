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

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import org.jetbrains.annotations.ApiStatus
import java.util.Collections
import java.util.EnumMap
import java.util.EnumSet
import java.util.Objects

/**
 * What one configured decision service can accept, as the service reports it. It always lists the
 * question kinds the service answers. It can also state which inputs the model reads natively, how
 * many questions fit in one request, which call options it understands, how many options or levels
 * a question of each kind may have, what evidence it reports for each kind, and what its confidence
 * figure measures.
 *
 * Everything past the question kinds is optional. A declaration that is null means the service
 * said nothing about it, which is different from stating an empty set. Create a value with [of],
 * then add declarations with the chain methods, each of which returns a new value. Kotlin callers
 * can also use the block form, `DecisionCapabilities(kinds) { maxQuestions = 8 }`.
 *
 * The kinds always iterate in the order [QuestionKind] declares them, and the string sets iterate
 * in sorted order, so `toString` and any serialized form come out the same on every run. JSON
 * leaves out every declaration that is not stated, so a kinds-only value writes exactly
 * `{"questionKinds":[...]}` as it always has.
 */
@ApiStatus.Experimental
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder(
    "questionKinds", "nativeInputKinds", "maxQuestions", "supportedCallOptionIds", "arity", "evidence", "confidenceMeasures",
)
class DecisionCapabilities private constructor(
    questionKinds: Set<QuestionKind>,
    nativeInputKinds: Set<String>?,
    maxQuestions: Int?,
    supportedCallOptionIds: Set<String>?,
    arities: Map<QuestionKind, DecisionArity>,
    evidence: Map<QuestionKind, Set<DecisionEvidenceKind>>,
    confidenceMeasures: Map<QuestionKind, ConfidenceMeasure>,
) {

    /**
     * The question kinds the service accepts. The set cannot be modified, holds at least one kind
     * and iterates in declaration order.
     */
    @get:JsonProperty("questionKinds")
    val questionKinds: Set<QuestionKind> = run {
        // EnumSet.copyOf cannot copy an empty plain set, so the emptiness check has to come first.
        require(questionKinds.isNotEmpty()) { "At least one question kind must be supported" }
        Collections.unmodifiableSet(EnumSet.copyOf(questionKinds))
    }

    /**
     * The input kinds the model reads natively, such as `text` or `image`, or null when the
     * service doesn't say.
     */
    @get:JsonProperty("nativeInputKinds")
    val nativeInputKinds: Set<String>? = nativeInputKinds?.let { kinds ->
        kinds.forEach { require(it.isNotBlank()) { "Native input kinds must not be blank" } }
        sortedCopy(kinds)
    }

    /** The most questions one request may hold, or null when the service doesn't say. */
    @get:JsonProperty("maxQuestions")
    val maxQuestions: Int? = maxQuestions?.also {
        require(it >= 1) { "maxQuestions must be at least 1, was $it" }
    }

    /**
     * The ids of the call options the service understands, or null when the service doesn't say.
     */
    @get:JsonProperty("supportedCallOptionIds")
    val supportedCallOptionIds: Set<String>? = supportedCallOptionIds?.let { ids ->
        ids.forEach { require(it.isNotBlank()) { "Call option ids must not be blank" } }
        sortedCopy(ids)
    }

    private val arities: Map<QuestionKind, DecisionArity> = perKind("arity", arities) { it }

    private val evidence: Map<QuestionKind, Set<DecisionEvidenceKind>> = perKind("evidence", evidence) {
        if (it.isEmpty()) emptySet() else Collections.unmodifiableSet(EnumSet.copyOf(it))
    }

    private val confidenceMeasures: Map<QuestionKind, ConfidenceMeasure> = perKind("a confidence measure", confidenceMeasures) { it }

    /**
     * Returns how many options or levels the service accepts in one question of a kind. For a
     * choice question it bounds the number of options, and for a rating question the number of
     * levels. A proposition question has no arity.
     *
     * @param kind the question kind
     * @return the arity, or null when the service doesn't say
     */
    fun arity(kind: QuestionKind): DecisionArity? = arities[kind]

    /**
     * Returns the evidence the service reports for a question kind.
     *
     * @param kind the question kind
     * @return the evidence kinds, possibly empty, or null when the service doesn't say
     */
    fun evidence(kind: QuestionKind): Set<DecisionEvidenceKind>? = evidence[kind]

    /**
     * Returns what the service's confidence figure measures for a question kind.
     *
     * @param kind the question kind
     * @return the measure, or null when the service doesn't say
     */
    fun confidenceMeasure(kind: QuestionKind): ConfidenceMeasure? = confidenceMeasures[kind]

    /**
     * Returns a copy that also reads the given input kind natively.
     *
     * @param kind the input kind, such as `text` or `image`
     * @return the new capabilities
     * @throws IllegalArgumentException if the kind is blank
     */
    fun acceptingInput(kind: String): DecisionCapabilities = copy(nativeInputKinds = (nativeInputKinds ?: emptySet()) + kind)

    /**
     * Returns a copy that also understands the given call option.
     *
     * @param key the option's key
     * @return the new capabilities
     */
    fun acceptingOption(key: DecisionOption<*>): DecisionCapabilities =
        copy(supportedCallOptionIds = (supportedCallOptionIds ?: emptySet()) + key.id)

    /**
     * Returns a copy that takes at most the given number of questions per request.
     *
     * @param max the limit, at least 1
     * @return the new capabilities
     * @throws IllegalArgumentException if the limit is below 1
     */
    fun withMaxQuestions(max: Int): DecisionCapabilities = copy(maxQuestions = max)

    /**
     * Returns a copy that accepts between min and max options or levels in each question of a
     * kind, replacing any arity already stated for it. For a choice question this bounds the number
     * of options, and for a rating question the number of levels. A proposition question has no
     * arity, so the service doesn't check one stated for it.
     *
     * @param kind a question kind the service accepts
     * @param min the fewest options or levels, at least 1
     * @param max the most options or levels, at least min
     * @return the new capabilities
     * @throws IllegalArgumentException if the bounds are out of order or the kind isn't accepted
     */
    fun withArity(kind: QuestionKind, min: Int, max: Int): DecisionCapabilities =
        copy(arities = arities + (kind to DecisionArity(min, max)))

    /**
     * Returns a copy that also reports the given evidence for a kind. Calling it with no evidence
     * states that the service reports nothing extra for that kind.
     *
     * @param kind a question kind the service accepts
     * @param evidence the evidence kinds it reports
     * @return the new capabilities
     * @throws IllegalArgumentException if the kind isn't accepted
     */
    fun reporting(kind: QuestionKind, vararg evidence: DecisionEvidenceKind): DecisionCapabilities =
        copy(evidence = this.evidence + (kind to (this.evidence[kind].orEmpty() + evidence)))

    /**
     * Returns a copy that states what the confidence figure measures for a kind, replacing any
     * measure already stated for it.
     *
     * @param kind a question kind the service accepts
     * @param measure what the confidence measures
     * @return the new capabilities
     * @throws IllegalArgumentException if the kind isn't accepted
     */
    fun withConfidenceMeasure(kind: QuestionKind, measure: ConfidenceMeasure): DecisionCapabilities =
        copy(confidenceMeasures = confidenceMeasures + (kind to measure))

    override fun equals(other: Any?): Boolean =
        this === other || other is DecisionCapabilities &&
            questionKinds == other.questionKinds &&
            nativeInputKinds == other.nativeInputKinds &&
            maxQuestions == other.maxQuestions &&
            supportedCallOptionIds == other.supportedCallOptionIds &&
            arities == other.arities &&
            evidence == other.evidence &&
            confidenceMeasures == other.confidenceMeasures

    override fun hashCode(): Int =
        Objects.hash(questionKinds, nativeInputKinds, maxQuestions, supportedCallOptionIds, arities, evidence, confidenceMeasures)

    override fun toString(): String = buildString {
        append("DecisionCapabilities(questionKinds=").append(questionKinds)
        nativeInputKinds?.let { append(", nativeInputKinds=").append(it) }
        maxQuestions?.let { append(", maxQuestions=").append(it) }
        supportedCallOptionIds?.let { append(", supportedCallOptionIds=").append(it) }
        if (arities.isNotEmpty()) append(", arity=").append(arities)
        if (evidence.isNotEmpty()) append(", evidence=").append(evidence)
        if (confidenceMeasures.isNotEmpty()) append(", confidenceMeasures=").append(confidenceMeasures)
        append(")")
    }

    private fun copy(
        nativeInputKinds: Set<String>? = this.nativeInputKinds,
        maxQuestions: Int? = this.maxQuestions,
        supportedCallOptionIds: Set<String>? = this.supportedCallOptionIds,
        arities: Map<QuestionKind, DecisionArity> = this.arities,
        evidence: Map<QuestionKind, Set<DecisionEvidenceKind>> = this.evidence,
        confidenceMeasures: Map<QuestionKind, ConfidenceMeasure> = this.confidenceMeasures,
    ): DecisionCapabilities = DecisionCapabilities(
        questionKinds, nativeInputKinds, maxQuestions, supportedCallOptionIds, arities, evidence, confidenceMeasures,
    )

    // Copies a per-kind map into declaration order, refusing kinds the service doesn't accept.
    private fun <V : Any> perKind(what: String, values: Map<QuestionKind, V>, copyValue: (V) -> V): Map<QuestionKind, V> {
        if (values.isEmpty()) return emptyMap()
        val copy = EnumMap<QuestionKind, V>(QuestionKind::class.java)
        values.forEach { (kind, value) ->
            require(kind in questionKinds) { "Cannot state $what for '${kind.wireName}', which the service does not accept" }
            copy[kind] = copyValue(value)
        }
        return Collections.unmodifiableMap(copy)
    }

    // The per-kind declarations as JSON objects keyed by the kind's wire name, left out when empty.
    @JsonProperty("arity")
    private fun arityJson(): Map<String, DecisionArity>? = byWireName(arities)

    @JsonProperty("evidence")
    private fun evidenceJson(): Map<String, Set<DecisionEvidenceKind>>? = byWireName(evidence)

    @JsonProperty("confidenceMeasures")
    private fun confidenceMeasuresJson(): Map<String, ConfidenceMeasure>? = byWireName(confidenceMeasures)

    /**
     * Rejects a JSON member this type doesn't define.
     *
     * @param name the unknown member's name
     * @param value the unknown member's value
     */
    @JsonAnySetter
    private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("DecisionCapabilities", name, value)

    /**
     * Creates decision capabilities.
     */
    companion object {

        /**
         * Returns capabilities with the given question kinds and nothing else stated.
         *
         * @param questionKinds the question kinds the service accepts, which must not be empty
         * @return the capabilities
         * @throws IllegalArgumentException if the set is empty
         */
        @JvmStatic
        fun of(questionKinds: Set<QuestionKind>): DecisionCapabilities =
            DecisionCapabilities(questionKinds, null, null, null, emptyMap(), emptyMap(), emptyMap())

        /**
         * Builds capabilities in a Kotlin block. The block and the chain methods give equal values
         * for the same declarations.
         *
         * @param questionKinds the question kinds the service accepts, which must not be empty
         * @param block the declarations
         * @return the capabilities
         * @throws IllegalArgumentException if any declaration is invalid
         */
        @JvmSynthetic
        operator fun invoke(questionKinds: Set<QuestionKind>, block: Scope.() -> Unit): DecisionCapabilities =
            Scope().apply(block).build(questionKinds)

        /**
         * Builds capabilities from deserialized JSON fields.
         *
         * @return the capabilities
         */
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        private fun fromJson(
            @JsonProperty("questionKinds", required = true) questionKinds: Set<QuestionKind>,
            @JsonProperty("nativeInputKinds") nativeInputKinds: Set<String?>?,
            @JsonProperty("maxQuestions") maxQuestions: Int?,
            @JsonProperty("supportedCallOptionIds") supportedCallOptionIds: Set<String?>?,
            @JsonProperty("arity") arity: Map<String, DecisionArity?>?,
            @JsonProperty("evidence") evidence: Map<String, Set<DecisionEvidenceKind?>?>?,
            @JsonProperty("confidenceMeasures") confidenceMeasures: Map<String, ConfidenceMeasure?>?,
        ): DecisionCapabilities = DecisionCapabilities(
            questionKinds,
            nativeInputKinds?.let { noNulls("nativeInputKinds", it) },
            maxQuestions,
            supportedCallOptionIds?.let { noNulls("supportedCallOptionIds", it) },
            byKind("arity", arity),
            byKind("evidence", evidence).mapValues { (_, kinds) -> noNulls("evidence", kinds) },
            byKind("confidenceMeasures", confidenceMeasures),
        )

        private fun sortedCopy(values: Set<String>): Set<String> = Collections.unmodifiableSet(LinkedHashSet(values.sorted()))

        private fun <V : Any> byWireName(values: Map<QuestionKind, V>): Map<String, V>? =
            if (values.isEmpty()) null else values.entries.associate { (kind, value) -> kind.wireName to value }

        private fun <T : Any> noNulls(member: String, values: Set<T?>): Set<T> {
            require(null !in values) { "$member must not hold null" }
            @Suppress("UNCHECKED_CAST")
            return values as Set<T>
        }

        private fun <V : Any> byKind(member: String, values: Map<String, V?>?): Map<QuestionKind, V> {
            if (values == null) return emptyMap()
            return values.entries.associate { (wireName, value) ->
                val kind = QuestionKind.entries.firstOrNull { it.wireName == wireName }
                    ?: throw IllegalArgumentException("Unknown question kind '$wireName' in $member")
                kind to requireNotNull(value) { "$member for '$wireName' must not be null" }
            }
        }
    }

    /**
     * The receiver of the Kotlin capabilities block. Each call adds one declaration, the same way
     * the matching chain method does.
     */
    @DecisionCapabilitiesDsl
    class Scope internal constructor() {

        private val inputs = mutableListOf<String>()
        private val options = mutableListOf<DecisionOption<*>>()
        private val arities = mutableListOf<Triple<QuestionKind, Int, Int>>()
        private val evidence = mutableListOf<Pair<QuestionKind, Array<out DecisionEvidenceKind>>>()
        private val measures = mutableListOf<Pair<QuestionKind, ConfidenceMeasure>>()

        /** The most questions one request may hold, or null to leave it unstated. */
        var maxQuestions: Int? = null

        /**
         * States that the model reads an input kind natively.
         *
         * @param kind the input kind, such as `text` or `image`
         */
        fun input(kind: String) {
            inputs += kind
        }

        /**
         * States that the service understands a call option.
         *
         * @param key the option's key
         */
        fun option(key: DecisionOption<*>) {
            options += key
        }

        /**
         * States how many options or levels each question of a kind may have. For a choice
         * question this bounds the number of options, and for a rating question the number of
         * levels. A proposition question has no arity, so the service doesn't check one stated
         * for it.
         *
         * @param kind a question kind the service accepts
         * @param min the fewest options or levels, at least 1
         * @param max the most options or levels, at least min
         */
        fun arity(kind: QuestionKind, min: Int, max: Int) {
            arities += Triple(kind, min, max)
        }

        /**
         * States evidence the service reports for a kind.
         *
         * @param kind a question kind the service accepts
         * @param evidence the evidence kinds it reports
         */
        fun reports(kind: QuestionKind, vararg evidence: DecisionEvidenceKind) {
            this.evidence += kind to evidence
        }

        /**
         * States what the confidence figure measures for a kind.
         *
         * @param kind a question kind the service accepts
         * @param measure what the confidence measures
         */
        fun confidence(kind: QuestionKind, measure: ConfidenceMeasure) {
            measures += kind to measure
        }

        internal fun build(questionKinds: Set<QuestionKind>): DecisionCapabilities {
            var result = of(questionKinds)
            inputs.forEach { result = result.acceptingInput(it) }
            options.forEach { result = result.acceptingOption(it) }
            maxQuestions?.let { result = result.withMaxQuestions(it) }
            arities.forEach { (kind, min, max) -> result = result.withArity(kind, min, max) }
            evidence.forEach { (kind, kinds) -> result = result.reporting(kind, *kinds) }
            measures.forEach { (kind, measure) -> result = result.withConfidenceMeasure(kind, measure) }
            return result
        }
    }
}
