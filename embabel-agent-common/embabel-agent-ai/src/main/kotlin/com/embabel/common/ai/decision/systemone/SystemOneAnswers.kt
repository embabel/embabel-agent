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
package com.embabel.common.ai.decision.systemone

import com.embabel.common.ai.classification.CategoryProbability
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.DISTRIBUTION_TOLERANCE
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.ChoiceQuestionSpec
import com.embabel.common.ai.decision.LevelProbability
import com.embabel.common.ai.decision.PropositionQuestionSpec
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.Question
import com.embabel.common.ai.decision.QuestionKind
import com.embabel.common.ai.decision.RatingQuestionSpec
import com.embabel.common.ai.decision.RatingResult
import com.embabel.common.ai.decision.RatingScore
import com.embabel.common.ai.decision.RatingStatistic
import com.embabel.common.ai.decision.spi.DecisionResponseAssembler
import com.embabel.common.ai.decision.spi.DecisionResponseAssembler.Anomaly
import tools.jackson.databind.JsonNode
import kotlin.math.abs

private const val HALF = 0.5

/**
 * The evidence rules for one System One answer. Each answer is handed to the assembler as an
 * outcome or as the reason it couldn't be read, so a bad answer fails only its own question.
 *
 * - A `noul` is the probability the proposition is true. Exactly 0.5 is inconclusive. A value
 *   outside 0..1 is out of range.
 * - A `choice` must report a probability for every option and no others, summing to 1. Zero
 *   confidence is inconclusive. A selection keeps the distribution in option order.
 * - A `score` must report a legend and probabilities keyed by every level index. The requested
 *   level order determines the mapping; legend values are informational and are not validated.
 *   The score is the expected level index and must lie between 0 and the last index.
 * - A missing or non-numeric field is unreadable. An answer of another known type is the wrong
 *   kind, and an unknown type is unreadable.
 */
internal object SystemOneAnswers {

    private val KINDS: Map<String, QuestionKind> = SystemOneCodec.TYPES.entries.associate { (kind, type) -> type to kind }

    /**
     * Reads one answer and records it against its question.
     *
     * @param assembler collects the outcomes
     * @param question the question this answer belongs to
     * @param answer the answer's JSON
     * @param provenance who answered
     * @return the assembler
     */
    fun read(
        assembler: DecisionResponseAssembler,
        question: Question<*>,
        answer: JsonNode,
        provenance: ModelProvenance,
    ): DecisionResponseAssembler {
        val kind = KINDS[SystemOneCodec.text(answer.get("type"))] ?: return assembler.unreadable(question.name)
        // Check the response's wire type against the requested question before reading its evidence.
        if (kind != question.kind) return wrongKind(assembler, question.name, kind)
        return when (question) {
            is PropositionQuestionSpec -> noul(assembler, question.name, answer, provenance)
            is ChoiceQuestionSpec -> choice(assembler, question, answer, provenance)
            is RatingQuestionSpec -> score(assembler, question, answer, provenance)
        }
    }

    /**
     * Reads a `noul` answer into a proposition outcome.
     *
     * @param assembler collects the outcomes
     * @param name the question name
     * @param answer the answer's JSON
     * @param provenance who answered
     * @return the assembler
     */
    private fun noul(
        assembler: DecisionResponseAssembler,
        name: String,
        answer: JsonNode,
        provenance: ModelProvenance,
    ): DecisionResponseAssembler {
        val probability = number(answer, "noul") ?: return assembler.unreadable(name)
        return when {
            !isProbability(probability) -> assembler.unreadable(name, Anomaly.OUT_OF_RANGE)
            probability == HALF -> assembler.proposition(name, PropositionResult.Inconclusive(provenance))
            else -> assembler.proposition(name, PropositionResult.Answered(probability > HALF, provenance, probability))
        }
    }

    /**
     * Reads a `choice` answer into a classification outcome.
     *
     * @param assembler collects the outcomes
     * @param question the choice question
     * @param answer the answer's JSON
     * @param provenance who answered
     * @return the assembler
     */
    private fun choice(
        assembler: DecisionResponseAssembler,
        question: ChoiceQuestionSpec,
        answer: JsonNode,
        provenance: ModelProvenance,
    ): DecisionResponseAssembler {
        val name = question.name
        val ids = question.options.map { it.id }
        val probabilities = distribution(answer.get("probabilities"), ids)
            ?: return assembler.unreadable(name, Anomaly.BAD_DISTRIBUTION)
        val confidence = number(answer, "confidence") ?: return assembler.unreadable(name)
        // Invalid confidence rejects the answer; zero is valid evidence for an inconclusive result.
        if (!isProbability(confidence)) return assembler.unreadable(name, Anomaly.OUT_OF_RANGE)
        if (confidence == 0.0) return assembler.choice(name, ClassificationResult.Inconclusive(provenance))
        val selected = SystemOneCodec.text(answer.get("choice")) ?: return assembler.unreadable(name)
        if (selected !in ids) {
            // The assembler reports a selection outside the options as out of domain.
            return assembler.choice(name, ClassificationResult.Selected(selected, provenance, confidence))
        }
        val distribution = ids.zip(probabilities) { id, probability -> CategoryProbability(id, probability) }
        return assembler.choice(name, ClassificationResult.Selected(selected, provenance, confidence, distribution))
    }

    /**
     * Reads a `score` answer into a rating outcome.
     *
     * @param assembler collects the outcomes
     * @param question the rating question
     * @param answer the answer's JSON
     * @param provenance who answered
     * @return the assembler
     */
    private fun score(
        assembler: DecisionResponseAssembler,
        question: RatingQuestionSpec,
        answer: JsonNode,
        provenance: ModelProvenance,
    ): DecisionResponseAssembler {
        val name = question.name
        // The protocol keys ratings by zero-based level index, not the application's level ids.
        val indexes = question.levels.indices.map(Int::toString)
        val probabilities = distribution(answer.get("probabilities"), indexes)
            ?: return assembler.unreadable(name, Anomaly.BAD_DISTRIBUTION)
        // Only legend indexes are authoritative; returned labels never remap the requested level order.
        if (!coversExactly(answer.get("legend"), indexes)) {
            return assembler.unreadable(name, Anomaly.BAD_DISTRIBUTION)
        }
        val score = number(answer, "score") ?: return assembler.unreadable(name)
        val confidence = number(answer, "confidence") ?: return assembler.unreadable(name)
        if (score !in 0.0..question.levels.lastIndex.toDouble() || !isProbability(confidence)) {
            return assembler.unreadable(name, Anomaly.OUT_OF_RANGE)
        }
        // Restore application level ids; the numeric score remains an expected ordinal index.
        val distribution = question.levels.zip(probabilities) { level, probability -> LevelProbability(level.id, probability) }
        val expected = RatingScore(score, RatingStatistic.EXPECTED_LEVEL_INDEX)
        return assembler.rating(name, RatingResult.Answered(provenance, null, distribution, expected, confidence))
    }

    /**
     * Records a failure under the answer's own kind, so the assembler reports the mismatch with
     * the question.
     *
     * @param assembler collects the outcomes
     * @param name the question name
     * @param kind the kind the answer has
     * @return the assembler
     */
    private fun wrongKind(assembler: DecisionResponseAssembler, name: String, kind: QuestionKind) = when (kind) {
        QuestionKind.PROPOSITION -> assembler.proposition(name, PropositionResult.Failure(FailureReason.INVALID_RESPONSE))
        QuestionKind.CHOICE -> assembler.choice(name, ClassificationResult.Failure(FailureReason.INVALID_RESPONSE))
        QuestionKind.RATING -> assembler.rating(name, RatingResult.Failure(FailureReason.INVALID_RESPONSE))
    }

    /**
     * Reads one probability per expected key, in [ids] order. Missing or extra keys, non-numeric
     * values and values outside 0..1 reject the distribution. Its sum may differ from 1 by at most
     * [DISTRIBUTION_TOLERANCE] in either direction.
     *
     * @param node the probabilities object
     * @param ids the keys it must cover
     * @return the probabilities in [ids] order, or null unless they cover exactly [ids] and sum to 1
     */
    private fun distribution(node: JsonNode?, ids: List<String>): List<Double>? {
        if (node == null || !coversExactly(node, ids)) return null
        val probabilities = ids.map { id -> number(node, id) ?: return null }
        val valid = probabilities.all(::isProbability) && abs(probabilities.sum() - 1.0) <= DISTRIBUTION_TOLERANCE
        return probabilities.takeIf { valid }
    }

    /**
     * Tells whether an object's JSON property names cover exactly [ids], with no missing or extra
     * keys and regardless of order. These keys are option ids for choices and ordinal indexes for ratings.
     *
     * @param node the object
     * @param ids the expected keys
     * @return true when the keys match
     */
    private fun coversExactly(node: JsonNode?, ids: List<String>): Boolean =
        node != null && node.isObject && node.propertyNames().toSet() == ids.toSet()

    /**
     * Reads a numeric field.
     *
     * @param node the object
     * @param field the field name
     * @return the value, or null when it is missing or not a number
     */
    private fun number(node: JsonNode, field: String): Double? =
        node.get(field)?.takeIf { it.isNumber }?.doubleValue()

    /**
     * Checks an unvalidated wire number against the inclusive probability range. NaN and infinities
     * also fail this comparison.
     *
     * @param value the value
     * @return true when it does
     */
    private fun isProbability(value: Double) = value in 0.0..1.0
}
