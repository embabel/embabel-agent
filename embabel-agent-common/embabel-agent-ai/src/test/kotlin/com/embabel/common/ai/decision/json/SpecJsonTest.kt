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
package com.embabel.common.ai.decision.json

import com.embabel.common.ai.decision.ChoiceQuestionSpec
import com.embabel.common.ai.decision.DecisionCapabilities
import com.embabel.common.ai.decision.DecisionOptions
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.ExecutionMode
import com.embabel.common.ai.decision.PropositionQuestionSpec
import com.embabel.common.ai.decision.Question
import com.embabel.common.ai.decision.QuestionKind
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.decision.RatingQuestionSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.exc.MismatchedInputException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

class SpecJsonTest {

    private val mapper: JsonMapper = JsonMapper.builder().addModule(DecisionJacksonModule()).build()

    // Found through META-INF/services, with no explicit registration.
    private val discovered: JsonMapper = JsonMapper.builder().findAndAddModules().build()

    private fun triage(): DecisionSpec = DecisionSpec.builder()
        .proposition("is_urgent") { it.asking("Does this convey urgency?") }
        .choice("department") {
            it.asking("Which team should handle this?")
                .option("billing", "Payments, invoicing, refunds")
                .option("technical", "Bugs, outages, integrations")
                .option("sales", "Pricing, upgrades, new accounts")
        }
        .rating("frustration") {
            it.asking("How frustrated is the customer?")
                .level("Calm")
                .level("Frustrated")
                .level("Very angry")
        }
        .build()

    private val urgentJson = """{"kind":"proposition","name":"is_urgent","instructions":"Does this convey urgency?"}"""

    private val departmentJson = """{"kind":"choice","name":"department","instructions":"Which team should handle this?",""" +
        """"options":[{"id":"billing","description":"Payments, invoicing, refunds"},""" +
        """{"id":"technical","description":"Bugs, outages, integrations"},""" +
        """{"id":"sales","description":"Pricing, upgrades, new accounts"}]}"""

    private val frustrationJson = """{"kind":"rating","name":"frustration","instructions":"How frustrated is the customer?",""" +
        """"levels":[{"id":"Calm","description":"Calm"},{"id":"Frustrated","description":"Frustrated"},""" +
        """{"id":"Very angry","description":"Very angry"}]}"""

    private val triageJson = """{"questions":[$urgentJson,$departmentJson,$frustrationJson]}"""

    private val requestJson = """{"input":"My invoice is wrong and nobody answers.","spec":$triageJson}"""

    private fun request(): DecisionRequest = DecisionRequest.of("My invoice is wrong and nobody answers.", triage())

    private fun assertRejects(json: String, type: Class<*>, vararg fragments: String, using: JsonMapper = mapper): MismatchedInputException {
        val error = assertThrows(MismatchedInputException::class.java) { using.readValue(json, type) }
        fragments.forEach { fragment ->
            assertTrue(error.message!!.contains(fragment)) { "Expected '$fragment' in: ${error.message}" }
        }
        return error
    }

    private fun spec(vararg questions: String): String = """{"questions":[${questions.joinToString(",")}]}"""

    @Nested
    inner class RoundTrip {

        @Test
        fun `the triage spec writes the golden JSON and reads back equal through both mappers`() {
            for (m in listOf(mapper, discovered)) {
                assertEquals(triageJson, m.writeValueAsString(triage()))
                val read = m.readValue(triageJson, DecisionSpec::class.java)
                assertEquals(triage(), read)
                assertEquals(triage().definitionId, read.definitionId)
            }
        }

        @Test
        fun `a request writes the golden JSON and reads back equal through both mappers`() {
            for (m in listOf(mapper, discovered)) {
                assertEquals(requestJson, m.writeValueAsString(request()))
                val read = m.readValue(requestJson, DecisionRequest::class.java)
                assertEquals(request(), read)
                assertEquals("My invoice is wrong and nobody answers.", read.input)
            }
        }

        @Test
        fun `an empty input survives the round trip`() {
            val empty = DecisionRequest.of("", triage())
            assertEquals(empty, mapper.readValue(mapper.writeValueAsString(empty), DecisionRequest::class.java))
        }

        @Test
        fun `each question reads through Question and through its own class`() {
            val cases = listOf(
                Triple(urgentJson, PropositionQuestionSpec::class.java, triage().questions[0]),
                Triple(departmentJson, ChoiceQuestionSpec::class.java, triage().questions[1]),
                Triple(frustrationJson, RatingQuestionSpec::class.java, triage().questions[2]),
            )
            for ((json, type, question) in cases) {
                assertEquals(json, mapper.writeValueAsString(question))
                assertEquals(question, mapper.readValue(json, Question::class.java))
                assertEquals(question, mapper.readValue(json, type))
            }
        }

        @Test
        fun `a list of questions reads through the Question binding`() {
            val read = mapper.readValue("[$urgentJson,$frustrationJson]", object : TypeReference<List<Question<*>>>() {})
            assertEquals(listOf(triage().questions[0], triage().questions[2]), read)
        }

        @Test
        fun `members may come in any order`() {
            val reordered = """{"levels":[{"description":"Low","id":"low"},{"id":"high","description":"High"}],""" +
                """"instructions":"How bad?","name":"severity","kind":"rating"}"""
            val expected = Questions.named("severity").rating("How bad?").level("low", "Low").level("high", "High").build()
            assertEquals(expected, mapper.readValue(reordered, Question::class.java))
        }

        @Test
        fun `options write the golden JSON and read back equal`() {
            val json = """{"executionModes":["native","single_question"]}"""
            assertEquals(json, mapper.writeValueAsString(DecisionOptions.defaults()))
            assertEquals(DecisionOptions.defaults(), mapper.readValue(json, DecisionOptions::class.java))
            val all = """{"executionModes":["native","single_question","sequential"]}"""
            assertEquals(all, discovered.writeValueAsString(DecisionOptions.allowingSequential()))
            assertEquals(DecisionOptions.allowingSequential(), discovered.readValue(all, DecisionOptions::class.java))
        }

        @Test
        fun `capabilities omit null limits and read back equal`() {
            val capabilities = DecisionCapabilities.of(
                setOf(QuestionKind.CHOICE, QuestionKind.PROPOSITION),
                setOf(ExecutionMode.SEQUENTIAL),
            ).withMaxQuestions(8)
            val json = """{"questionKinds":["proposition","choice"],"executionModes":["sequential"],"maxQuestions":8}"""
            assertEquals(json, mapper.writeValueAsString(capabilities))
            assertEquals(capabilities, mapper.readValue(json, DecisionCapabilities::class.java))

            val both = capabilities.withMaxInputCharacters(20000)
            val bothJson = """{"questionKinds":["proposition","choice"],"executionModes":["sequential"],""" +
                """"maxQuestions":8,"maxInputCharacters":20000}"""
            assertEquals(bothJson, discovered.writeValueAsString(both))
            assertEquals(both, discovered.readValue(bothJson, DecisionCapabilities::class.java))
        }

        @Test
        fun `an explicit null limit reads as an unreported limit`() {
            val json = """{"questionKinds":["rating"],"executionModes":["native"],"maxQuestions":null}"""
            val expected = DecisionCapabilities.of(setOf(QuestionKind.RATING), setOf(ExecutionMode.NATIVE))
            assertEquals(expected, mapper.readValue(json, DecisionCapabilities::class.java))
        }

        @Test
        fun `the mapper's naming strategy changes neither the written nor the accepted names`() {
            for (strategy in listOf(PropertyNamingStrategies.SNAKE_CASE, PropertyNamingStrategies.UPPER_CAMEL_CASE)) {
                val renaming = JsonMapper.builder()
                    .addModule(DecisionJacksonModule())
                    .propertyNamingStrategy(strategy)
                    .build()
                assertEquals(requestJson, renaming.writeValueAsString(request()))
                assertEquals(request(), renaming.readValue(requestJson, DecisionRequest::class.java))
                val capabilities = DecisionCapabilities.of(setOf(QuestionKind.RATING), setOf(ExecutionMode.NATIVE))
                    .withMaxInputCharacters(100)
                val json = """{"questionKinds":["rating"],"executionModes":["native"],"maxInputCharacters":100}"""
                assertEquals(json, renaming.writeValueAsString(capabilities))
                assertEquals(capabilities, renaming.readValue(json, DecisionCapabilities::class.java))
            }
        }
    }

    @Nested
    inner class Rejections {

        @Test
        fun `a duplicate member inside a question is rejected`() {
            val json = spec("""{"kind":"proposition","name":"a","name":"b","instructions":"Is it?"}""")
            assertRejects(json, DecisionSpec::class.java, "Duplicate member 'name' in Question")
        }

        @Test
        fun `a duplicate member at spec level is rejected`() {
            val json = """{"questions":[$urgentJson],"questions":[$frustrationJson]}"""
            assertRejects(json, DecisionSpec::class.java, "Duplicate member 'questions' in DecisionSpec")
        }

        @Test
        fun `a duplicate member inside an option or at request level is rejected`() {
            val option = """{"kind":"choice","name":"c","instructions":"Which?","options":[{"id":"a","id":"b","description":"x"}]}"""
            assertRejects(option, Question::class.java, "Duplicate member 'id' in Category")
            val request = """{"input":"a","input":"b","spec":$triageJson}"""
            assertRejects(request, DecisionRequest::class.java, "Duplicate member 'input' in DecisionRequest")
        }

        @Test
        fun `a mapper whose global defaults allow duplicates and unknown members still rejects them`() {
            val lenient = JsonMapper.builder()
                .addModule(DecisionJacksonModule())
                .disable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build()
            assertFalse(lenient.isEnabled(StreamReadFeature.STRICT_DUPLICATE_DETECTION))
            assertFalse(lenient.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
            assertRejects(
                spec("""{"kind":"proposition","name":"a","instructions":"Is it?","instructions":"Is it not?"}"""),
                DecisionSpec::class.java,
                "Duplicate member 'instructions' in Question",
                using = lenient,
            )
            assertRejects(
                """{"questions":[$urgentJson],"questions":[$urgentJson]}""",
                DecisionSpec::class.java,
                "Duplicate member 'questions' in DecisionSpec",
                using = lenient,
            )
            assertRejects(
                """{"executionModes":["native"],"executionModes":["sequential"]}""",
                DecisionOptions::class.java,
                "Duplicate member 'executionModes' in DecisionOptions",
                using = lenient,
            )
            assertRejects(
                """{"questions":[$urgentJson],"name":"triage"}""",
                DecisionSpec::class.java,
                "Unknown member 'name' in DecisionSpec",
                using = lenient,
            )
        }

        @Test
        fun `unknown members are rejected at every level`() {
            assertRejects("""{"questions":[$urgentJson],"version":2}""", DecisionSpec::class.java, "Unknown member 'version' in DecisionSpec")
            assertRejects(
                spec("""{"kind":"proposition","name":"a","instructions":"Is it?","weight":1}"""),
                DecisionSpec::class.java,
                "Unknown member 'weight' in Question",
            )
            assertRejects(
                """{"input":"x","spec":$triageJson,"options":{}}""",
                DecisionRequest::class.java,
                "Unknown member 'options' in DecisionRequest",
            )
            assertRejects(
                """{"kind":"rating","name":"r","instructions":"How?","levels":[{"id":"a","description":"A","rank":1},{"id":"b","description":"B"}]}""",
                Question::class.java,
                "Unknown member 'rank' in RatingLevel",
            )
            assertRejects(
                """{"questionKinds":["rating"],"executionModes":["native"],"maxTokens":5}""",
                DecisionCapabilities::class.java,
                "Unknown member 'maxTokens' in DecisionCapabilities",
            )
        }

        @Test
        fun `a member that belongs to another kind is rejected`() {
            assertRejects(
                """{"kind":"proposition","name":"p","instructions":"Is it?","options":[{"id":"a","description":"A"}]}""",
                Question::class.java,
                "Unknown member 'options' in PropositionQuestionSpec",
            )
            assertRejects(
                """{"kind":"choice","name":"c","instructions":"Which?","options":[{"id":"a","description":"A"}],""" +
                    """"levels":[{"id":"a","description":"A"},{"id":"b","description":"B"}]}""",
                Question::class.java,
                "Unknown member 'levels' in ChoiceQuestionSpec",
            )
            assertRejects(
                """{"kind":"choice","name":"c","instructions":"Which?","levels":[{"id":"a","description":"A"}]}""",
                ChoiceQuestionSpec::class.java,
                "Unknown member 'levels' in ChoiceQuestionSpec",
            )
        }

        @Test
        fun `an unknown kind is rejected`() {
            assertRejects(
                spec("""{"kind":"poll","name":"a","instructions":"Is it?"}"""),
                DecisionSpec::class.java,
                "Member 'kind' in Question must be one of proposition, choice, rating",
            )
            // Wire names are lower case, so the enum constant name is not accepted either.
            assertRejects(
                """{"kind":"PROPOSITION","name":"a","instructions":"Is it?"}""",
                Question::class.java,
                "Member 'kind' in Question must be one of proposition, choice, rating",
            )
        }

        @Test
        fun `a question read as its own class must carry that kind`() {
            assertRejects(urgentJson, ChoiceQuestionSpec::class.java, "Member 'kind' in ChoiceQuestionSpec must be choice")
        }

        @Test
        fun `duplicate question names are rejected`() {
            assertRejects(
                spec(urgentJson, """{"kind":"proposition","name":"is_urgent","instructions":"Is it urgent?"}"""),
                DecisionSpec::class.java,
                "Question names must be unique within a decision spec. Repeated: 'is_urgent'",
            )
        }

        @Test
        fun `an empty question list is rejected`() {
            assertRejects("""{"questions":[]}""", DecisionSpec::class.java, "A decision spec needs at least one question")
        }

        @Test
        fun `a rating with one level is rejected`() {
            val error = assertRejects(
                spec("""{"kind":"rating","name":"frustration","instructions":"How?","levels":[{"id":"Calm","description":"Calm"}]}"""),
                DecisionSpec::class.java,
                "Question 'frustration': at least two levels are required",
            )
            // The validation failure is kept as the cause.
            assertInstanceOf(IllegalArgumentException::class.java, error.cause)
        }

        @Test
        fun `a choice with duplicate option ids is rejected`() {
            assertRejects(
                """{"kind":"choice","name":"department","instructions":"Which?",""" +
                    """"options":[{"id":"billing","description":"A"},{"id":"billing","description":"B"}]}""",
                Question::class.java,
                "Question 'department': option ids must be unique. Repeated: 'billing'",
            )
        }

        @Test
        fun `a choice without options is rejected`() {
            assertRejects(
                """{"kind":"choice","name":"c","instructions":"Which?"}""",
                Question::class.java,
                "Missing required member 'options' in ChoiceQuestionSpec",
            )
            assertRejects(
                """{"kind":"choice","name":"c","instructions":"Which?","options":[]}""",
                Question::class.java,
                "Question 'c': at least one option is required",
            )
        }

        @Test
        fun `missing instructions are rejected`() {
            assertRejects(
                spec("""{"kind":"proposition","name":"a"}"""),
                DecisionSpec::class.java,
                "Missing required member 'instructions' in Question",
            )
            assertRejects(
                """{"kind":"proposition","name":"a","instructions":"  "}""",
                Question::class.java,
                "Question 'a': instructions must not be blank",
            )
        }

        @Test
        fun `a blank name and missing request members are rejected`() {
            assertRejects("""{"kind":"proposition","name":" ","instructions":"Is it?"}""", Question::class.java, "Question name must not be blank")
            assertRejects("""{"spec":$triageJson}""", DecisionRequest::class.java, "Missing required member 'input' in DecisionRequest")
            assertRejects("""{"input":"x"}""", DecisionRequest::class.java, "Missing required member 'spec' in DecisionRequest")
        }

        @Test
        fun `a nested spec inside a request is read strictly`() {
            assertRejects(
                """{"input":"x","spec":{"questions":[$urgentJson,$urgentJson]}}""",
                DecisionRequest::class.java,
                "Repeated: 'is_urgent'",
            )
        }

        @Test
        fun `a value of the wrong token type is rejected`() {
            assertRejects(
                """{"kind":"proposition","name":"a","instructions":5}""",
                Question::class.java,
                "Member 'instructions' in Question must be a string",
            )
            assertRejects("""{"questions":{}}""", DecisionSpec::class.java, "Member 'questions' in DecisionSpec must be an array")
            assertRejects("""{"questions":["is_urgent"]}""", DecisionSpec::class.java, "Member 'questions[0]' in DecisionSpec must be an object")
            assertRejects("""{"input":null,"spec":$triageJson}""", DecisionRequest::class.java, "Member 'input' in DecisionRequest must be a string")
            assertRejects("""[$urgentJson]""", DecisionSpec::class.java, "DecisionSpec must be an object")
            assertRejects(
                """{"questionKinds":["rating"],"executionModes":["native"],"maxQuestions":"8"}""",
                DecisionCapabilities::class.java,
                "Member 'maxQuestions' in DecisionCapabilities must be an integer",
            )
        }

        @Test
        fun `unknown, repeated and empty enum values are rejected`() {
            assertRejects(
                """{"executionModes":["native","parallel"]}""",
                DecisionOptions::class.java,
                "Member 'executionModes[1]' in DecisionOptions must be one of native, single_question, sequential",
            )
            assertRejects(
                """{"executionModes":["NATIVE"]}""",
                DecisionOptions::class.java,
                "Member 'executionModes[0]' in DecisionOptions must be one of native, single_question, sequential",
            )
            assertRejects(
                """{"executionModes":["native","native"]}""",
                DecisionOptions::class.java,
                "Member 'executionModes[1]' in DecisionOptions must be a value not listed earlier",
            )
            assertRejects("""{"executionModes":[]}""", DecisionOptions::class.java, "At least one execution mode must be allowed")
            assertRejects(
                """{"questionKinds":["essay"],"executionModes":["native"]}""",
                DecisionCapabilities::class.java,
                "Member 'questionKinds[0]' in DecisionCapabilities must be one of proposition, choice, rating",
            )
        }

        @Test
        fun `capability limits below one are rejected`() {
            assertRejects(
                """{"questionKinds":["rating"],"executionModes":["native"],"maxQuestions":0}""",
                DecisionCapabilities::class.java,
                "maxQuestions must be at least 1 when present",
            )
            assertRejects(
                """{"questionKinds":["rating"],"executionModes":["native"],"maxInputCharacters":-3}""",
                DecisionCapabilities::class.java,
                "maxInputCharacters must be at least 1 when present",
            )
        }
    }

    @Nested
    inner class TreeToValue {

        private fun triageTree(): ObjectNode = mapper.readTree(triageJson) as ObjectNode

        @Test
        fun `a valid tree reads back equal`() {
            assertEquals(triage(), mapper.treeToValue(triageTree(), DecisionSpec::class.java))
            assertEquals(triage(), mapper.treeToValue(mapper.valueToTree(triage()), DecisionSpec::class.java))
        }

        @Test
        fun `an unknown kind in a tree is rejected`() {
            val tree = triageTree()
            ((tree.get("questions") as ArrayNode).get(1) as ObjectNode).put("kind", "poll")
            val error = assertThrows(MismatchedInputException::class.java) { mapper.treeToValue(tree, DecisionSpec::class.java) }
            assertTrue(error.message!!.contains("Member 'kind' in Question must be one of proposition, choice, rating")) { error.message!! }
        }

        @Test
        fun `duplicate question names in a tree are rejected`() {
            val tree = triageTree()
            ((tree.get("questions") as ArrayNode).get(2) as ObjectNode).put("name", "department")
            val error = assertThrows(MismatchedInputException::class.java) { mapper.treeToValue(tree, DecisionSpec::class.java) }
            assertTrue(error.message!!.contains("Repeated: 'department'")) { error.message!! }
        }

        @Test
        fun `an unknown member and a broken rating in a tree are rejected`() {
            val unknown = triageTree().put("owner", "support")
            val first = assertThrows(MismatchedInputException::class.java) { mapper.treeToValue(unknown, DecisionSpec::class.java) }
            assertTrue(first.message!!.contains("Unknown member 'owner' in DecisionSpec")) { first.message!! }

            val oneLevel = triageTree()
            val levels = ((oneLevel.get("questions") as ArrayNode).get(2) as ObjectNode).get("levels") as ArrayNode
            levels.remove(2)
            levels.remove(1)
            val second = assertThrows(MismatchedInputException::class.java) { mapper.treeToValue(oneLevel, DecisionSpec::class.java) }
            assertTrue(second.message!!.contains("at least two levels are required")) { second.message!! }
        }
    }
}
