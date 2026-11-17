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

import com.embabel.common.ai.decision.DecisionEvidenceKind.CATEGORY_DISTRIBUTION
import com.embabel.common.ai.decision.DecisionEvidenceKind.CONFIDENCE
import com.embabel.common.ai.decision.DecisionEvidenceKind.LEVEL_DISTRIBUTION
import com.embabel.common.ai.decision.DecisionEvidenceKind.REFUSAL
import com.embabel.common.ai.decision.DecisionEvidenceKind.TRUE_PROBABILITY
import com.embabel.common.ai.decision.QuestionKind.CHOICE
import com.embabel.common.ai.decision.QuestionKind.PROPOSITION
import com.embabel.common.ai.decision.QuestionKind.RATING
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tools.jackson.databind.DatabindException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

class DecisionCapabilitiesDeclarationsTest {

    private val mapper: JsonMapper = JsonMapper.builder().build()
    private val discovered: JsonMapper = JsonMapper.builder().findAndAddModules().build()
    private val lenient: JsonMapper = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()

    private val effort = DecisionOption.of("acme", "effort", String::class.java)

    private fun chained(): DecisionCapabilities = DecisionCapabilities.of(setOf(PROPOSITION, CHOICE, RATING))
        .acceptingInput("text")
        .acceptingInput("image")
        .acceptingOption(DecisionOptions.TIMEOUT)
        .acceptingOption(effort)
        .withMaxQuestions(8)
        .withArity(CHOICE, 1, 3)
        .reporting(PROPOSITION, TRUE_PROBABILITY)
        .reporting(CHOICE, CATEGORY_DISTRIBUTION, REFUSAL)
        .reporting(RATING, LEVEL_DISTRIBUTION, CONFIDENCE)
        .withConfidenceMeasure(RATING, ConfidenceMeasure("acme.concentration"))

    @Test
    fun `the block and the chain build equal values`() {
        val built = DecisionCapabilities(setOf(PROPOSITION, CHOICE, RATING)) {
            input("image")
            input("text")
            option(effort)
            option(DecisionOptions.TIMEOUT)
            maxQuestions = 8
            arity(CHOICE, 1, 3)
            reports(PROPOSITION, TRUE_PROBABILITY)
            reports(CHOICE, CATEGORY_DISTRIBUTION, REFUSAL)
            reports(RATING, LEVEL_DISTRIBUTION, CONFIDENCE)
            confidence(RATING, ConfidenceMeasure("acme.concentration"))
        }
        assertEquals(chained(), built)
        assertEquals(chained().hashCode(), built.hashCode())
    }

    @Test
    fun `every declaration reads back as stated`() {
        val capabilities = chained()
        assertEquals(setOf("text", "image"), capabilities.nativeInputKinds)
        assertEquals(8, capabilities.maxQuestions)
        assertEquals(setOf("embabel.timeout", "acme.effort"), capabilities.supportedCallOptionIds)
        assertEquals(DecisionArity(1, 3), capabilities.arity(CHOICE))
        assertNull(capabilities.arity(RATING))
        assertEquals(setOf(CATEGORY_DISTRIBUTION, REFUSAL), capabilities.evidence(CHOICE))
        assertEquals(ConfidenceMeasure("acme.concentration"), capabilities.confidenceMeasure(RATING))
        assertNull(capabilities.confidenceMeasure(CHOICE))
    }

    @Test
    fun `a kinds-only value states nothing else`() {
        val capabilities = DecisionCapabilities.of(setOf(CHOICE))
        assertNull(capabilities.nativeInputKinds)
        assertNull(capabilities.maxQuestions)
        assertNull(capabilities.supportedCallOptionIds)
        assertNull(capabilities.arity(CHOICE))
        assertNull(capabilities.evidence(CHOICE))
        assertNull(capabilities.confidenceMeasure(CHOICE))
    }

    @Test
    fun `chain methods leave the original value unchanged`() {
        val original = DecisionCapabilities.of(setOf(CHOICE))
        original.withMaxQuestions(3).acceptingInput("text")
        assertEquals(DecisionCapabilities.of(setOf(CHOICE)), original)
        assertNull(original.maxQuestions)
    }

    @Test
    fun `the legacy JSON form is unchanged for a kinds-only value`() {
        val capabilities = DecisionCapabilities.of(setOf(CHOICE))
        for (m in listOf(mapper, discovered)) {
            assertEquals("""{"questionKinds":["choice"]}""", m.writeValueAsString(capabilities))
        }
    }

    @Test
    fun `a kinds-only document still reads`() {
        for (m in listOf(mapper, discovered)) {
            assertEquals(
                DecisionCapabilities.of(setOf(CHOICE)),
                m.readValue("""{"questionKinds":["choice"]}""", DecisionCapabilities::class.java),
            )
        }
    }

    @Test
    fun `an enriched value round-trips through both mappers`() {
        for (m in listOf(mapper, discovered)) {
            val json = m.writeValueAsString(chained())
            assertEquals(chained(), m.readValue(json, DecisionCapabilities::class.java))
        }
    }

    @Test
    fun `an enriched value writes only the members it states`() {
        val json = mapper.writeValueAsString(DecisionCapabilities.of(setOf(CHOICE)).withMaxQuestions(4))
        assertEquals("""{"questionKinds":["choice"],"maxQuestions":4}""", json)
    }

    @Test
    fun `an unknown member is still rejected`() {
        for (m in listOf(mapper, lenient)) {
            assertThrows(DatabindException::class.java) {
                m.readValue("""{"questionKinds":["choice"],"maxQuestions":2,"maxTokens":5}""", DecisionCapabilities::class.java)
            }
        }
    }

    @Test
    fun `invalid declarations in a document are rejected`() {
        for (json in listOf(
            """{"questionKinds":["choice"],"maxQuestions":0}""",
            """{"questionKinds":["choice"],"arity":{"choice":{"min":3,"max":2}}}""",
            """{"questionKinds":["choice"],"arity":{"essay":{"min":1,"max":2}}}""",
            """{"questionKinds":["choice"],"evidence":{"choice":["guesswork"]}}""",
            """{"questionKinds":["choice"],"confidenceMeasures":{"choice":" "}}""",
        )) {
            assertThrows(DatabindException::class.java) { mapper.readValue(json, DecisionCapabilities::class.java) }
        }
    }

    @Test
    fun `not stated differs from stated as empty`() {
        val notStated = DecisionCapabilities.of(setOf(CHOICE))
        val noInputs = mapper.readValue("""{"questionKinds":["choice"],"nativeInputKinds":[]}""", DecisionCapabilities::class.java)
        assertEquals(emptySet<String>(), noInputs.nativeInputKinds)
        assertEquals(notStated, DecisionCapabilities(setOf(CHOICE)) { })
        assertNotEquals(notStated, noInputs)

        val noEvidence = notStated.reporting(CHOICE)
        assertEquals(emptySet<DecisionEvidenceKind>(), noEvidence.evidence(CHOICE))
        assertNotEquals(notStated, noEvidence)

        for (m in listOf(mapper, discovered)) {
            assertEquals(noInputs, m.readValue(m.writeValueAsString(noInputs), DecisionCapabilities::class.java))
            assertEquals(noEvidence, m.readValue(m.writeValueAsString(noEvidence), DecisionCapabilities::class.java))
        }
    }

    @Test
    fun `equality covers every declaration`() {
        val base = chained()
        assertNotEquals(base, base.acceptingInput("audio"))
        assertNotEquals(base, base.withMaxQuestions(9))
        assertNotEquals(base, base.withArity(CHOICE, 1, 4))
        assertNotEquals(base, base.reporting(PROPOSITION, CONFIDENCE))
        assertNotEquals(base, base.withConfidenceMeasure(RATING, ConfidenceMeasure("acme.margin")))
        assertNotEquals(base, base.acceptingOption(DecisionOption.of("acme", "temperature", Double::class.javaObjectType)))
    }

    @Test
    fun `withMaxQuestions refuses zero`() {
        assertThrows(IllegalArgumentException::class.java) { DecisionCapabilities.of(setOf(CHOICE)).withMaxQuestions(0) }
    }

    @Test
    fun `withArity refuses a minimum above the maximum`() {
        assertThrows(IllegalArgumentException::class.java) { DecisionCapabilities.of(setOf(CHOICE)).withArity(CHOICE, 3, 2) }
        assertThrows(IllegalArgumentException::class.java) { DecisionArity(0, 2) }
    }

    @Test
    fun `declarations for a kind the service does not accept are refused`() {
        val choiceOnly = DecisionCapabilities.of(setOf(CHOICE))
        assertThrows(IllegalArgumentException::class.java) { choiceOnly.withArity(RATING, 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { choiceOnly.reporting(RATING, CONFIDENCE) }
        assertThrows(IllegalArgumentException::class.java) {
            choiceOnly.withConfidenceMeasure(RATING, ConfidenceMeasure("acme.concentration"))
        }
    }

    @Test
    fun `a blank input kind or confidence measure is refused`() {
        assertThrows(IllegalArgumentException::class.java) { DecisionCapabilities.of(setOf(CHOICE)).acceptingInput(" ") }
        assertThrows(IllegalArgumentException::class.java) { ConfidenceMeasure("") }
    }
}
