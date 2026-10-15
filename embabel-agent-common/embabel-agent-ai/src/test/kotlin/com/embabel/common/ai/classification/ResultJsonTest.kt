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
package com.embabel.common.ai.classification

import com.embabel.common.ai.decision.PropositionResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import tools.jackson.databind.DatabindException
import tools.jackson.databind.json.JsonMapper

class ResultJsonTest {

    private val mapper: JsonMapper = JsonMapper.builder().build()

    private val bare = ModelProvenance("jev-latest", "typesafe")
    private val full = ModelProvenance("jev-latest", "typesafe", "2026-09", "req-42")

    private val bareJson = """{"modelName":"jev-latest","provider":"typesafe"}"""
    private val fullJson = """{"modelName":"jev-latest","provider":"typesafe","version":"2026-09","requestId":"req-42"}"""

    /**
     * Checks that a value writes exactly the expected JSON and reads back equal to itself.
     *
     * @param value the value to write
     * @param json the exact JSON it must write
     * @param type the type to read the JSON back as
     */
    private fun <T : Any> assertRoundTrip(value: T, json: String, type: Class<in T>) {
        assertEquals(json, mapper.writeValueAsString(value))
        assertEquals(value, mapper.readValue(json, type))
    }

    /**
     * Checks that reading the JSON fails and that the error names the problem.
     *
     * @param json the JSON to read
     * @param type the type to read it as
     * @param fragment text the error message must contain
     */
    private fun assertRejects(json: String, type: Class<*>, fragment: String) {
        val error = assertThrows(DatabindException::class.java) { mapper.readValue(json, type) }
        assertTrue(error.message!!.contains(fragment)) { "Expected '$fragment' in: ${error.message}" }
    }

    @Nested
    inner class Provenance {

        @Test
        fun `leaves out an absent version and request ID`() {
            assertRoundTrip(bare, bareJson, ModelProvenance::class.java)
        }

        @Test
        fun `writes version and request ID when present`() {
            assertRoundTrip(full, fullJson, ModelProvenance::class.java)
        }

        @Test
        fun `rejects an unknown member and a missing model name`() {
            assertRejects(
                """{"modelName":"jev-latest","provider":"typesafe","apiKey":"x"}""",
                ModelProvenance::class.java,
                "Unknown member 'apiKey' in ModelProvenance",
            )
            assertRejects("""{"provider":"typesafe"}""", ModelProvenance::class.java, "modelName")
        }
    }

    @Nested
    inner class Reasons {

        @Test
        fun `failure reasons are written in lower case`() {
            assertRoundTrip(FailureReason.UNAVAILABLE, "\"unavailable\"", FailureReason::class.java)
            assertRoundTrip(FailureReason.INVALID_RESPONSE, "\"invalid_response\"", FailureReason::class.java)
        }

        @Test
        fun `rejects the enum constant name`() {
            assertThrows(DatabindException::class.java) { mapper.readValue("\"UNAVAILABLE\"", FailureReason::class.java) }
        }
    }

    @Nested
    inner class Categories {

        @Test
        fun `round trips id and description`() {
            assertRoundTrip(Category("a", "A"), """{"id":"a","description":"A"}""", Category::class.java)
        }

        @Test
        fun `rejects an unknown member, a missing description and a blank id`() {
            assertRejects("""{"id":"a","description":"A","alias":"x"}""", Category::class.java, "Unknown member 'alias' in Category")
            assertRejects("""{"id":"a"}""", Category::class.java, "description")
            assertRejects("""{"id":" ","description":"A"}""", Category::class.java, "Category ID must not be blank")
        }
    }

    @Nested
    inner class Classification {

        private val type = ClassificationResult::class.java

        @Test
        fun `selected writes its status, confidence and full provenance`() {
            assertRoundTrip(
                ClassificationResult.Selected("b", full, 0.91),
                """{"status":"selected","categoryId":"b","confidence":0.91,"provenance":$fullJson}""",
                type,
            )
        }

        @Test
        fun `selected without confidence leaves it out`() {
            assertRoundTrip(
                ClassificationResult.Selected("a", bare),
                """{"status":"selected","categoryId":"a","provenance":$bareJson}""",
                type,
            )
        }

        @Test
        fun `no match, inconclusive and failure each have their own status`() {
            assertRoundTrip(ClassificationResult.NoMatch(bare), """{"status":"no_match","provenance":$bareJson}""", type)
            assertRoundTrip(ClassificationResult.Inconclusive(bare), """{"status":"inconclusive","provenance":$bareJson}""", type)
            assertRoundTrip(
                ClassificationResult.Failure(FailureReason.UNAVAILABLE),
                """{"status":"failure","reason":"unavailable"}""",
                type,
            )
        }

        @Test
        fun `rejects an unknown member in every variant`() {
            listOf(
                """{"status":"selected","categoryId":"a","provenance":$bareJson,"extra":1}""",
                """{"status":"no_match","provenance":$bareJson,"extra":1}""",
                """{"status":"inconclusive","provenance":$bareJson,"extra":1}""",
                """{"status":"failure","reason":"unavailable","extra":1}""",
            ).forEach { assertRejects(it, type, "Unknown member 'extra' in ClassificationResult") }
        }

        @Test
        fun `rejects an unknown status and out of range confidence`() {
            assertThrows(DatabindException::class.java) {
                mapper.readValue("""{"status":"maybe","provenance":$bareJson}""", type)
            }
            assertRejects(
                """{"status":"selected","categoryId":"a","confidence":1.5,"provenance":$bareJson}""",
                type,
                "Confidence must be finite and between 0 and 1",
            )
        }
    }

    @Nested
    inner class Proposition {

        private val type = PropositionResult::class.java

        @Test
        fun `answered writes its answer and probability of truth`() {
            assertRoundTrip(
                PropositionResult.Answered(true, bare, 0.93),
                """{"status":"answered","answer":true,"pTrue":0.93,"provenance":$bareJson}""",
                type,
            )
        }

        @Test
        fun `answered without probability of truth leaves it out`() {
            assertRoundTrip(
                PropositionResult.Answered(false, full),
                """{"status":"answered","answer":false,"provenance":$fullJson}""",
                type,
            )
        }

        @Test
        fun `inconclusive and failure each have their own status`() {
            assertRoundTrip(PropositionResult.Inconclusive(bare), """{"status":"inconclusive","provenance":$bareJson}""", type)
            assertRoundTrip(
                PropositionResult.Failure(FailureReason.INVALID_RESPONSE),
                """{"status":"failure","reason":"invalid_response"}""",
                type,
            )
        }

        @Test
        fun `rejects an unknown member in every variant`() {
            listOf(
                """{"status":"answered","answer":true,"provenance":$bareJson,"extra":1}""",
                """{"status":"inconclusive","provenance":$bareJson,"extra":1}""",
                """{"status":"failure","reason":"unavailable","extra":1}""",
            ).forEach { assertRejects(it, type, "Unknown member 'extra' in PropositionResult") }
        }

        @Test
        fun `rejects a missing answer`() {
            assertRejects("""{"status":"answered","provenance":$bareJson}""", type, "answer")
        }
    }
}
