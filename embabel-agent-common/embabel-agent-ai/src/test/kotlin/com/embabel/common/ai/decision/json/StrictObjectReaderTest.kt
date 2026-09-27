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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.core.JsonParser
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.exc.StreamReadException
import tools.jackson.core.json.JsonReadFeature
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.exc.MismatchedInputException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.module.SimpleModule
import tools.jackson.databind.node.ObjectNode

/** Small value types that exist only to drive the reader through a real Jackson deserializer. */
private data class Sample(
    val name: String,
    val flag: Boolean? = null,
    val score: Double? = null,
    val count: Int? = null,
    val nested: Nested? = null,
    val tags: List<String>? = null,
    val others: List<Nested>? = null,
)

private data class Nested(val label: String)

private val SAMPLE_READER = StrictObjectReader(
    Sample::class.java,
    required = listOf("name"),
    optional = listOf("flag", "score", "count", "nested", "tags", "others", "forgotten"),
)

private val NESTED_READER = StrictObjectReader(Nested::class.java, required = listOf("label"))

private fun readNested(read: ((String, StrictObjectReader.MemberValue) -> Unit) -> Unit): Nested {
    var label: String? = null
    read { member, value -> if (member == "label") label = value.string() }
    return Nested(label!!)
}

private class NestedDeserializer : ValueDeserializer<Nested>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): Nested =
        readNested { handler -> NESTED_READER.read(parser, context, handler) }
}

private class SampleDeserializer : ValueDeserializer<Sample>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): Sample {
        var sample = Sample(name = "")
        SAMPLE_READER.read(parser, context) { member, value ->
            // "forgotten" is allowed but deliberately never read, to check the consumption guard.
            when (member) {
                "name" -> sample = sample.copy(name = value.string())
                "flag" -> sample = sample.copy(flag = value.boolean())
                "score" -> sample = sample.copy(score = value.orNull { it.double() })
                "count" -> sample = sample.copy(count = value.int())
                "nested" -> sample = sample.copy(nested = readNested { value.readObject(NESTED_READER, it) })
                "tags" -> sample = sample.copy(tags = buildList { value.readArray { add(it.string()) } })
                "others" -> sample = sample.copy(
                    others = buildList { value.readArray { add(it.readValue(Nested::class.java)) } },
                )
            }
        }
        return sample
    }
}

class StrictObjectReaderTest {

    private val module = SimpleModule()
        .addDeserializer(Sample::class.java, SampleDeserializer())
        .addDeserializer(Nested::class.java, NestedDeserializer())

    private val mapper: JsonMapper = JsonMapper.builder().addModule(module).build()

    private fun read(json: String, using: JsonMapper = mapper): Sample = using.readValue(json, Sample::class.java)

    private fun assertRejects(json: String, message: String, using: JsonMapper = mapper) {
        val error = assertThrows(MismatchedInputException::class.java) { read(json, using) }
        assertEquals(message, error.originalMessage)
    }

    @Test
    fun `reads every member kind in any order`() {
        val json = """
            {"others":[{"label":"o1"},{"label":"o2"}],"tags":["x","y"],
             "nested":{"label":"n"},"count":3,"score":0.25,"flag":true,"name":"sample"}
        """.trimIndent()
        assertEquals(
            Sample(
                name = "sample",
                flag = true,
                score = 0.25,
                count = 3,
                nested = Nested("n"),
                tags = listOf("x", "y"),
                others = listOf(Nested("o1"), Nested("o2")),
            ),
            read(json),
        )
    }

    @Test
    fun `optional members may be omitted and an optional null is accepted where the handler allows it`() {
        assertEquals(Sample(name = "only"), read("""{"name":"only"}"""))
        assertEquals(Sample(name = "only"), read("""{"name":"only","score":null}"""))
    }

    @Test
    fun `an integer token is accepted where a double is read`() {
        assertEquals(Sample(name = "n", score = 1.0), read("""{"name":"n","score":1}"""))
    }

    @Test
    fun `duplicate member at the top level is rejected`() {
        assertRejects("""{"name":"a","name":"b"}""", "Duplicate member 'name' in Sample")
    }

    @Test
    fun `duplicate member inside a nested object is rejected`() {
        assertRejects(
            """{"name":"a","nested":{"label":"x","label":"y"}}""",
            "Duplicate member 'label' in Nested",
        )
        assertRejects(
            """{"name":"a","others":[{"label":"x","label":"y"}]}""",
            "Duplicate member 'label' in Nested",
        )
    }

    @Test
    fun `a mapper with parser duplicate detection switched off still reports duplicates`() {
        val lenient = JsonMapper.builder()
            .addModule(module)
            .disable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build()
        assertTrue(!lenient.isEnabled(StreamReadFeature.STRICT_DUPLICATE_DETECTION))
        assertRejects("""{"name":"a","name":"b"}""", "Duplicate member 'name' in Sample", lenient)
        assertRejects(
            """{"name":"a","nested":{"label":"x","label":"y"}}""",
            "Duplicate member 'label' in Nested",
            lenient,
        )
    }

    @Test
    fun `unknown member is rejected at the top level and inside a nested object`() {
        assertRejects("""{"name":"a","extra":1}""", "Unknown member 'extra' in Sample")
        assertRejects("""{"name":"a","nested":{"label":"x","colour":"red"}}""", "Unknown member 'colour' in Nested")
    }

    @Test
    fun `missing required member is rejected at the top level and inside a nested object`() {
        assertRejects("""{"flag":true}""", "Missing required member 'name' in Sample")
        assertRejects("""{}""", "Missing required member 'name' in Sample")
        assertRejects("""{"name":"a","nested":{}}""", "Missing required member 'label' in Nested")
    }

    @Test
    fun `wrong token type is rejected for each typed read`() {
        assertRejects("""{"name":true}""", "Member 'name' in Sample must be a string")
        assertRejects("""{"name":null}""", "Member 'name' in Sample must be a string")
        assertRejects("""{"name":"a","flag":"yes"}""", "Member 'flag' in Sample must be a boolean")
        assertRejects("""{"name":"a","nested":[]}""", "Member 'nested' in Sample must be an object")
        assertRejects("""{"name":"a","tags":{}}""", "Member 'tags' in Sample must be an array")
        assertRejects("""{"name":"a","tags":["x",1]}""", "Member 'tags[1]' in Sample must be a string")
        assertRejects("""{"name":"a","count":1.5}""", "Member 'count' in Sample must be an integer")
        assertRejects("""{"name":"a","count":3000000000}""", "Member 'count' in Sample must be an integer")
    }

    @Test
    fun `a string where a number is required is rejected`() {
        assertRejects("""{"name":"a","score":"0.5"}""", "Member 'score' in Sample must be a number")
        assertRejects("""{"name":"a","count":"3"}""", "Member 'count' in Sample must be an integer")
    }

    @Test
    fun `a number that parses to infinity is rejected by the finite check`() {
        assertRejects("""{"name":"a","score":1e999}""", "Member 'score' in Sample must be a finite number")
        assertRejects("""{"name":"a","score":-1e999}""", "Member 'score' in Sample must be a finite number")
    }

    @Test
    fun `literal NaN is rejected by the parser before the reader sees it`() {
        val error = assertThrows(StreamReadException::class.java) { read("""{"name":"a","score":NaN}""") }
        assertTrue(error !is MismatchedInputException)
        assertTrue(error.originalMessage.contains("NaN"), error.originalMessage)
    }

    @Test
    fun `NaN admitted by a lenient parser is still rejected by the finite check`() {
        val lenient = JsonMapper.builder()
            .addModule(module)
            .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
            .build()
        assertRejects("""{"name":"a","score":NaN}""", "Member 'score' in Sample must be a finite number", lenient)
    }

    @Test
    fun `a value that is not an object is rejected`() {
        assertRejects("""["name"]""", "Sample must be an object")
        assertRejects("\"name\"", "Sample must be an object")
    }

    @Test
    fun `a handler that leaves an allowed member unread is a programming error`() {
        val error = assertThrows(RuntimeException::class.java) { read("""{"name":"a","forgotten":1}""") }
        val cause = generateSequence<Throwable>(error) { it.cause }.firstOrNull { it is IllegalStateException }
        assertTrue(cause != null, "expected an IllegalStateException in $error")
        assertEquals("Member 'forgotten' in Sample was not read", cause!!.message)
    }

    @Test
    fun `treeToValue on an ObjectNode cannot see a duplicate member because the tree kept only one value`() {
        val tree = mapper.readTree("""{"name":"first","name":"second"}""")
        assertTrue(tree is ObjectNode)
        assertEquals(1, tree.size())
        // The reader walks the tree's tokens and sees one "name" member, so the duplicate goes undetected.
        assertEquals(Sample(name = "second"), mapper.treeToValue(tree, Sample::class.java))
    }

    @Test
    fun `reader rejects a member listed as both required and optional`() {
        assertThrows(IllegalArgumentException::class.java) {
            StrictObjectReader(Sample::class.java, required = listOf("name"), optional = listOf("name"))
        }
    }
}
