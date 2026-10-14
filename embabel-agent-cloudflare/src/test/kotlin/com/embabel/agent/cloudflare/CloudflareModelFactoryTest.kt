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
package com.embabel.agent.cloudflare

import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.decision.RatingResult
import com.embabel.common.ai.decision.UnsupportedDecisionException
import com.embabel.common.byok.InvalidApiKeyException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

class CloudflareModelFactoryTest {

    private val account = "0123456789abcdef0123456789abcdef"
    private val base = "https://api.cloudflare.com/client/v4/accounts/$account"
    private val urgent = Questions.named("urgent").proposition("Is this urgent?").build()
    private val mapper = JsonMapper.builder().build()

    private val answers = """{"model":"clef","answers":{"q1":{"type":"noul","noul":0.2}},"usage":{"input_tokens":18,"output_tokens":0}}"""

    private fun envelope(result: String = answers, success: Boolean = true, errors: String = "[]") =
        """{"success":$success,"errors":$errors,"messages":[],"result":$result}"""

    private class Mocked(val factory: CloudflareModelFactory, val server: MockRestServiceServer)

    private fun mocked(token: Supplier<String?> = Supplier { "cf-token" }): Mocked {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return Mocked(CloudflareModelFactory(account, token, builder), server)
    }

    private fun ask(factory: CloudflareModelFactory, spec: DecisionSpec = DecisionSpec.of(urgent), model: String? = null) =
        (model?.let(factory::build) ?: factory.build()).ask(DecisionRequest.of("A parcel came back unopened.", spec))

    @Nested
    inner class Requests {

        @Test
        fun `runs clef on the account with the token and unwraps the envelope`() {
            val mocked = mocked()
            var body: String? = null
            var headers: HttpHeaders? = null
            mocked.server.expect(requestTo("$base/ai/run/@cf/cloudflare/clef"))
                .andExpect(method(HttpMethod.POST))
                .andExpect { request ->
                    body = (request as MockClientHttpRequest).bodyAsString
                    headers = request.headers
                }
                .andRespond(withSuccess(envelope(), MediaType.APPLICATION_JSON).header("cf-ray", "8f1c2a3b4d5e6f70-SJC"))

            val response = ask(mocked.factory)

            mocked.server.verify()
            assertEquals("clef", mapper.readTree(body).get("model").stringValue())
            assertEquals("Bearer cf-token", headers!!.getFirst(HttpHeaders.AUTHORIZATION))
            assertEquals(
                PropositionResult.Answered(false, ModelProvenance("clef", "Cloudflare", null, "8f1c2a3b4d5e6f70-SJC"), 0.2),
                response.answer(urgent),
            )
        }

        @Test
        fun `clef flash is sent by its short name`() {
            val mocked = mocked()
            var body: String? = null
            mocked.server.expect(requestTo("$base/ai/run/@cf/cloudflare/clef-flash"))
                .andExpect { body = (it as MockClientHttpRequest).bodyAsString }
                .andRespond(withSuccess(envelope(), MediaType.APPLICATION_JSON))

            ask(mocked.factory, model = CloudflareModelFactory.CLEF_FLASH)

            assertEquals("clef-flash", mapper.readTree(body).get("model").stringValue())
        }

        @Test
        fun `clef omni is sent by its short name`() {
            val mocked = mocked()
            var body: String? = null
            mocked.server.expect(requestTo("$base/ai/run/@cf/cloudflare/clef-omni"))
                .andExpect { body = (it as MockClientHttpRequest).bodyAsString }
                .andRespond(withSuccess(envelope(), MediaType.APPLICATION_JSON))

            assertEquals(null, ask(mocked.factory, model = CloudflareModelFactory.CLEF_OMNI).requestFailure)

            assertEquals("clef-omni", mapper.readTree(body).get("model").stringValue())
        }

        @Test
        fun `jev runs on the account's shared route with the request as its input`() {
            val mocked = mocked()
            var body: String? = null
            val jev = """{"model":"jev-1.13.0","answers":{"q1":{"type":"noul","noul":0.95}},"usage":{"input_tokens":40,"output_tokens":0}}"""
            mocked.server.expect(requestTo("$base/ai/run"))
                .andExpect(method(HttpMethod.POST))
                .andExpect { body = (it as MockClientHttpRequest).bodyAsString }
                .andRespond(withSuccess(envelope(result = jev), MediaType.APPLICATION_JSON).header("cf-ray", "9a-SJC"))

            val response = ask(mocked.factory, model = CloudflareModelFactory.JEV)

            val sent = mapper.readTree(body)
            assertEquals(setOf("model", "input"), sent.propertyNames().toSet())
            assertEquals("typesafe/jev", sent.get("model").stringValue())
            assertEquals(setOf("state", "questions"), sent.get("input").propertyNames().toSet())
            assertEquals("noul", sent.get("input").get("questions").get("q1").get("type").stringValue())
            assertEquals(
                PropositionResult.Answered(true, ModelProvenance("jev-1.13.0", "Cloudflare", null, "9a-SJC"), 0.95),
                response.answer(urgent),
            )
        }

        @Test
        fun `jev's body is written without reading the request back`() {
            val mocked = mocked()
            val longId = "o".repeat(50_001)
            val choice = Questions.named("pick").choice("Pick").option(longId, "Long").option("short", "Short").build()
            var body: String? = null
            mocked.server.expect(requestTo("$base/ai/run"))
                .andExpect { body = (it as MockClientHttpRequest).bodyAsString }
                .andRespond(withSuccess(envelope(), MediaType.APPLICATION_JSON))

            ask(mocked.factory, DecisionSpec.of(choice), CloudflareModelFactory.JEV)

            assertTrue(body!!.startsWith("""{"model":"typesafe/jev","input":{"state":"""), body!!.take(80))
            assertTrue(body!!.contains(longId))
        }

        @Test
        fun `a third party model name that isn't author and model fails the build and sends nothing`() {
            val mocked = mocked()

            listOf("typesafe/jev/extra", "jev", "TypeSafe/Jev", "clef", "../../user/tokens").forEach { model ->
                val e = assertThrows<IllegalArgumentException>(model) { mocked.factory.build(model) }
                assertEquals("A third-party Workers AI model id looks like <author>/<model>", e.message, model)
            }
            mocked.server.verify()
        }

        @Test
        fun `a hosted model id that isn't author and model fails the build and sends nothing`() {
            val mocked = mocked()

            listOf("@cf/../../user/tokens", "@cf/cloudflare/clef?x=1", "@cf/clef").forEach { model ->
                val e = assertThrows<IllegalArgumentException>(model) { mocked.factory.build(model) }
                assertEquals("A Workers AI model id looks like @cf/<author>/<model>", e.message, model)
            }
            mocked.server.verify()
        }

        @Test
        fun `a missing token fails the call without sending it`() {
            val mocked = mocked(token = { null })

            assertEquals(FailureReason.UNAVAILABLE, ask(mocked.factory).requestFailure)
            mocked.server.verify()
        }
    }

    @Nested
    inner class Envelope {

        @Test
        fun `an envelope that reports failure or has no result fails the request`() {
            listOf(
                envelope(success = false),
                envelope(errors = """[{"code":10000,"message":"SENTINEL"}]"""),
                envelope(result = "null"),
                envelope(result = "[]"),
                answers,
            ).forEach { body ->
                val mocked = mocked()
                mocked.server.expect(requestTo("$base/ai/run/@cf/cloudflare/clef")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON))

                assertEquals(FailureReason.INVALID_RESPONSE, ask(mocked.factory).requestFailure, body)
            }
        }
    }

    @Nested
    inner class Limits {

        private fun refused(spec: DecisionSpec) {
            val calls = AtomicInteger()
            val mocked = mocked(token = { "cf-token".also { calls.incrementAndGet() } })

            assertThrows<UnsupportedDecisionException> { ask(mocked.factory, spec) }

            assertEquals(0, calls.get())
            mocked.server.verify()
        }

        @Test
        fun `more than 64 questions are refused before anything is sent`() {
            refused(DecisionSpec.of((1..65).map { Questions.named("q$it").proposition("Question $it?").build() }))
        }

        @Test
        fun `a choice needs 2 to 255 options`() {
            refused(DecisionSpec.of(Questions.named("one").choice("Pick").option("only", "Only").build()))
            val many = Questions.named("many").choice("Pick")
            (1..256).forEach { many.option("o$it", "Option $it") }
            refused(DecisionSpec.of(many.build()))
        }

        @Test
        fun `a rating takes at most 10 levels`() {
            val levels = Questions.named("scale").rating("Rate")
            (1..11).forEach { levels.level("l$it") }
            refused(DecisionSpec.of(levels.build()))
        }

        @Test
        fun `the clef limits don't apply to jev`() {
            val mocked = mocked()
            var body: String? = null
            val probabilities = (0..10).joinToString(",") { """"$it":${if (it == 3) 1 else 0}""" }
            val legend = (0..10).joinToString(",") { """"$it":"l${it + 1}"""" }
            val score = """{"model":"jev-1.13.0","answers":{"q1":{"type":"score","score":3,"legend":{$legend},"probabilities":{$probabilities},"confidence":0.9}},"usage":{"input_tokens":40,"output_tokens":0}}"""
            mocked.server.expect(requestTo("$base/ai/run"))
                .andExpect { body = (it as MockClientHttpRequest).bodyAsString }
                .andRespond(withSuccess(envelope(result = score), MediaType.APPLICATION_JSON))
            val levels = Questions.named("scale").rating("Rate")
            (1..11).forEach { levels.level("l$it") }
            val scale = levels.build()

            val response = ask(mocked.factory, DecisionSpec.of(scale), CloudflareModelFactory.JEV)

            val criteria = mapper.readTree(body).get("input").get("questions").get("q1").get("criteria")
            assertEquals((1..11).map { "l$it" }, criteria.values().map { it.stringValue() })
            assertTrue(response.answer(scale) is RatingResult.Answered, response.answer(scale).toString())
        }
    }

    @Nested
    inner class Byok {

        private val listing = envelope(result = """[{"name":"@cf/cloudflare/clef"},{"name":"@cf/cloudflare/clef-flash"},{"id":"no-name"}]""")

        @Test
        fun `validation searches the account's models with the token`() {
            val mocked = mocked()
            mocked.server.expect(requestTo("$base/ai/models/search?search=clef"))
                .andExpect(method(HttpMethod.GET))
                .andExpect { assertEquals("Bearer cf-token", it.headers.getFirst(HttpHeaders.AUTHORIZATION)) }
                .andRespond(withSuccess(listing, MediaType.APPLICATION_JSON))

            assertEquals(CloudflareModelFactory.CLEF, mocked.factory.buildValidated().name)
        }

        @Test
        fun `model names come from the search result`() {
            val mocked = mocked()
            mocked.server.expect(requestTo("$base/ai/models/search?search=clef")).andRespond(withSuccess(listing, MediaType.APPLICATION_JSON))

            assertEquals(listOf(CloudflareModelFactory.CLEF, CloudflareModelFactory.CLEF_FLASH), mocked.factory.modelNames())
        }

        @Test
        fun `a refused token is invalid`() {
            val mocked = mocked()
            mocked.server.expect(requestTo("$base/ai/models/search?search=clef")).andRespond(withStatus(HttpStatus.FORBIDDEN))

            assertThrows<InvalidApiKeyException> { mocked.factory.buildValidated() }
        }
    }

    @Nested
    inner class Construction {

        @Test
        fun `the account id must be 32 hexadecimal characters and stays out of the message`() {
            listOf("short", "0123456789abcdef0123456789abcdeg", "../0123456789abcdef0123456789abcd").forEach { id ->
                val e = assertThrows<IllegalArgumentException>(id) { CloudflareModelFactory(id, { "t" }) }
                assertFalse(e.message!!.contains(id), id)
            }
        }

        @Test
        fun `builds clef by default and never reads the token while building`() {
            val calls = AtomicInteger()
            val factory = CloudflareModelFactory(account, { "t".also { calls.incrementAndGet() } })

            val service = factory.build()

            assertEquals(CloudflareModelFactory.CLEF, service.name)
            assertEquals(CloudflareModelFactory.PROVIDER, service.provider)
            assertEquals(0, calls.get())
        }
    }
}
