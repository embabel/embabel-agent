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

import org.jetbrains.annotations.ApiStatus
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.databind.DeserializationContext

/**
 * Reads one JSON object token by token and rejects anything the target type does not allow.
 *
 * The decision deserializers use it so that duplicate members are caught from the token stream
 * whatever the mapper's parser settings are. It also rejects unknown members, missing required
 * members and values of the wrong JSON type. Every rejection goes through
 * [DeserializationContext.reportInputMismatch] with one of these messages:
 *
 * - `Duplicate member '<name>' in <Type>`
 * - `Unknown member '<name>' in <Type>`
 * - `Missing required member '<name>' in <Type>`
 * - `Member '<name>' in <Type> must be <expected>`
 *
 * `<Type>` is the simple class name of [target]. A reader holds no parse state, so one instance
 * can be shared by every call for its type. It never builds a tree, because a tree keeps only
 * the last value of a repeated member and the duplicate would be lost.
 *
 * @property target the type being read, named in every message
 */
@ApiStatus.Internal
internal class StrictObjectReader private constructor(
    val target: Class<*>,
    private val required: Set<String>,
    private val allowed: Set<String>?,
) {

    /**
     * Creates a reader for an object whose member names are fixed.
     *
     * @param target the type being read
     * @param required members that must appear; the first absent one in this order is reported
     * @param optional members that may appear
     */
    constructor(target: Class<*>, required: Collection<String>, optional: Collection<String> = emptyList()) :
        this(target = target, required = LinkedHashSet(required), allowed = LinkedHashSet(required) + optional) {
        val overlap = required.intersect(optional.toSet())
        require(overlap.isEmpty()) { "Members listed as both required and optional: $overlap" }
    }

    private val typeName: String = target.simpleName

    /**
     * Reads the object that starts at the parser's current token.
     *
     * The handler is called once per member, in document order, after the duplicate and unknown
     * checks pass. It must read the value exactly once through the [MemberValue] it receives.
     * Required members are checked after the closing brace. When this returns, the parser sits on
     * that closing brace.
     *
     * @param parser a parser positioned on the object's opening brace
     * @param context the context used to report rejections
     * @param handler receives each member name and its value
     */
    fun read(parser: JsonParser, context: DeserializationContext, handler: (String, MemberValue) -> Unit) {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            fail(context, "%s must be an object", typeName)
        }
        readMembers(parser, context, handler)
    }

    private fun readMembers(parser: JsonParser, context: DeserializationContext, handler: (String, MemberValue) -> Unit) {
        val seen = HashSet<String>()
        var name = parser.nextName()
        while (name != null) {
            if (!seen.add(name)) duplicateMember(context, name)
            if (allowed != null && name !in allowed) unknownMember(context, name)
            parser.nextToken()
            val value = MemberValue(name, this, parser, context)
            handler(name, value)
            value.checkRead()
            name = parser.nextName()
        }
        // A text parser fails on truncated input by itself; a token buffer can simply run out.
        if (parser.currentToken() != JsonToken.END_OBJECT) {
            fail(context, "Unexpected end of input in %s", typeName)
        }
        required.firstOrNull { it !in seen }?.let { missingMember(context, it) }
    }

    /** Reports `Duplicate member '<name>' in <Type>`. */
    fun duplicateMember(context: DeserializationContext, name: String): Nothing =
        fail(context, "Duplicate member '%s' in %s", name, typeName)

    /** Reports `Unknown member '<name>' in <Type>`. */
    fun unknownMember(context: DeserializationContext, name: String): Nothing =
        fail(context, "Unknown member '%s' in %s", name, typeName)

    /** Reports `Missing required member '<name>' in <Type>`. */
    fun missingMember(context: DeserializationContext, name: String): Nothing =
        fail(context, "Missing required member '%s' in %s", name, typeName)

    /**
     * Reports `Member '<name>' in <Type> must be <expected>`.
     *
     * @param expected a short phrase such as `a string` or `one of native, sequential`
     */
    fun invalidMember(context: DeserializationContext, name: String, expected: String): Nothing =
        fail(context, "Member '%s' in %s must be %s", name, typeName, expected)

    private fun fail(context: DeserializationContext, format: String, vararg args: Any): Nothing {
        // The member name travels as an argument so a '%' inside it cannot break the format.
        context.reportInputMismatch<Any?>(target, format, *args)
        throw IllegalStateException("reportInputMismatch returned without throwing")
    }

    /**
     * One member value, or one array element, waiting to be read.
     *
     * Each instance must be read exactly once, by one of its typed reads. Reading it twice, or
     * returning from a handler without reading it, throws [IllegalStateException] because that
     * is a bug in the deserializer and says nothing about the input.
     *
     * @property name the member name, or `<member>[<index>]` for an array element
     */
    @ApiStatus.Internal
    internal class MemberValue internal constructor(
        val name: String,
        private val owner: StrictObjectReader,
        private val parser: JsonParser,
        private val context: DeserializationContext,
    ) {
        private var read = false

        /** True when the value is JSON `null`. Checking this does not count as reading it. */
        val isNull: Boolean
            get() = parser.currentToken() == JsonToken.VALUE_NULL

        /** Reads a JSON string. */
        fun string(): String {
            markRead()
            if (parser.currentToken() != JsonToken.VALUE_STRING) invalid("a string")
            return parser.getString()
        }

        /** Reads a JSON `true` or `false`. */
        fun boolean(): Boolean {
            markRead()
            return when (parser.currentToken()) {
                JsonToken.VALUE_TRUE -> true
                JsonToken.VALUE_FALSE -> false
                else -> invalid("a boolean")
            }
        }

        /**
         * Reads a JSON number as a finite double. Integer literals are accepted. A literal too
         * large for a double, such as `1e999`, and a `NaN` let through by a lenient parser are
         * both rejected as `a finite number`.
         */
        fun double(): Double {
            markRead()
            if (parser.currentToken()?.isNumeric != true) invalid("a number")
            val number = parser.getDoubleValue()
            if (!number.isFinite()) invalid("a finite number")
            return number
        }

        /** Reads a JSON integer literal that fits in an `Int`. Fractions and larger values are rejected. */
        fun int(): Int {
            markRead()
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT ||
                parser.getNumberType() != JsonParser.NumberType.INT
            ) {
                invalid("an integer")
            }
            return parser.getIntValue()
        }

        /**
         * Returns null for JSON `null` and otherwise hands this value to [read].
         *
         * @param read one of this value's typed reads
         */
        fun <T> orNull(read: (MemberValue) -> T): T? {
            if (isNull) {
                markRead()
                return null
            }
            return read(this)
        }

        /**
         * Reads a nested JSON object with another reader, which applies its own member rules
         * and names its own type in messages.
         *
         * @param reader the reader for the nested object's type
         * @param handler receives each nested member name and its value
         */
        fun readObject(reader: StrictObjectReader, handler: (String, MemberValue) -> Unit) {
            markRead()
            if (parser.currentToken() != JsonToken.START_OBJECT) invalid("an object")
            reader.readMembers(parser, context, handler)
        }

        /**
         * Reads a JSON array, calling [handler] once per element in order. Each element must be
         * read exactly once, and its messages use the name `<member>[<index>]`.
         *
         * @param handler receives each element
         */
        fun readArray(handler: (MemberValue) -> Unit) {
            markRead()
            if (parser.currentToken() != JsonToken.START_ARRAY) invalid("an array")
            var index = 0
            while (true) {
                when (parser.nextToken()) {
                    JsonToken.END_ARRAY -> return
                    // Reached only from a token buffer that runs out; a text parser fails first.
                    null -> owner.fail(context, "Unexpected end of input in %s", owner.typeName)
                    else -> {
                        val element = MemberValue("$name[$index]", owner, parser, context)
                        handler(element)
                        element.checkRead()
                        index++
                    }
                }
            }
        }

        /**
         * Reads the value with the deserializer the context finds for [type]. Duplicate checks
         * inside that value are only as strict as that deserializer.
         *
         * @param type the type to read
         */
        fun <T> readValue(type: Class<T>): T {
            markRead()
            return context.readValue(parser, type)
        }

        /**
         * Reports `Member '<name>' in <Type> must be <expected>` for this value.
         *
         * @param expected a short phrase such as `a string` or `one of native, sequential`
         */
        fun invalid(expected: String): Nothing = owner.invalidMember(context, name, expected)

        internal fun checkRead() {
            check(read) { "Member '$name' in ${owner.typeName} was not read" }
        }

        private fun markRead() {
            check(!read) { "Member '$name' in ${owner.typeName} was read twice" }
            read = true
        }
    }

    companion object {
        /**
         * Creates a reader for an object used as a map, where any member name is allowed and
         * none is required. Repeated names are still rejected.
         *
         * @param target the type named in messages
         */
        fun anyMembers(target: Class<*>): StrictObjectReader = StrictObjectReader(target = target, required = emptySet(), allowed = null)
    }
}
