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

import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.deser.std.StdDeserializer

/**
 * Reads a decision request from JSON by looking at the whole object. A request has an input or a
 * state, never both, and a creator with one parameter for each would be refused by a mapper that
 * insists on every creator property being present.
 */
internal class DecisionRequestDeserializer : StdDeserializer<DecisionRequest>(DecisionRequest::class.java) {

    override fun deserialize(parser: JsonParser, context: DeserializationContext): DecisionRequest {
        val tree = readExactTree(parser, context)
        if (!tree.isObject) return reject(context, "A decision request must be a JSON object")
        return try {
            for ((name, value) in tree.properties()) {
                if (name !in MEMBERS) rejectUnknownMember("DecisionRequest", name, context.readTreeAsValue(value, Any::class.java))
            }
            val spec = present(tree, "spec") ?: return reject(context, "A decision request needs a 'spec'")
            val input = present(tree, "input")
            val state = present(tree, "state")
            require((input == null) != (state == null)) { "A decision request needs exactly one of 'input' or 'state'" }
            val questions = context.readTreeAsValue(spec, DecisionSpec::class.java)
            if (state != null) {
                DecisionRequest.of(state, questions)
            } else {
                DecisionRequest.of(context.readTreeAsValue(input, String::class.java), questions)
            }
        } catch (e: IllegalArgumentException) {
            reject(context, e.message ?: "The decision request is not valid")
        }
    }

    /**
     * Reads the request as a tree with every decimal kept exactly as written. The request is copied
     * into a buffer that holds decimals as BigDecimal, then read back through the caller's own
     * context, so the caller's checks, such as refusing duplicate keys, still apply. Only the
     * request's own tokens are copied, which leaves the parser ready for whatever follows it.
     *
     * @param parser the parser, positioned on the request
     * @param context the read in progress
     * @return the request as a tree
     */
    private fun readExactTree(parser: JsonParser, context: DeserializationContext): JsonNode {
        val buffer = context.bufferForInputBuffering(parser).forceUseOfBigDecimal(true)
        var token = parser.currentToken()
        if (token == JsonToken.PROPERTY_NAME || token == JsonToken.END_OBJECT) {
            // Jackson can hand over an object it has already opened, so copy the rest of its members.
            buffer.writeStartObject()
            while (token == JsonToken.PROPERTY_NAME) {
                buffer.copyCurrentStructure(parser)
                token = parser.nextToken()
            }
            buffer.writeEndObject()
        } else {
            buffer.copyCurrentStructure(parser)
        }
        return buffer.asParserOnFirstToken(context, parser).use { context.readTree(it) }
    }

    /**
     * Returns a member's value, treating an explicit null like a missing member.
     *
     * @param tree the request object
     * @param name the member name
     * @return the value, or null when the member is missing or null
     */
    private fun present(tree: JsonNode, name: String): JsonNode? = tree.get(name)?.takeUnless { it.isNull }

    /**
     * Fails the read the way Jackson reports any input it cannot use.
     *
     * @param context the read in progress
     * @param message what is wrong with the request
     */
    private fun reject(context: DeserializationContext, message: String): Nothing =
        context.reportInputMismatch<Nothing>(DecisionRequest::class.java, "%s", message)

    private companion object {
        private val MEMBERS = setOf("input", "state", "spec")
    }
}
