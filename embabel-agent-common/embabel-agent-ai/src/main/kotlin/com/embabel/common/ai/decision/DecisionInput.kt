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
import tools.jackson.databind.JsonNode

/**
 * The material a decision reasons over, such as a passage of text or a JSON document.
 *
 * Each input says what form it takes through [kind], so a service can tell whether it accepts it.
 */
@ApiStatus.Experimental
interface DecisionInput {

    /** The form this input takes, such as [TEXT] or [JSON]. */
    val kind: String

    /**
     * The input kinds the framework defines.
     */
    companion object {

        /** The kind of a plain text input. */
        const val TEXT: String = "text"

        /** The kind of a JSON object or array input. */
        const val JSON: String = "json"
    }
}

/**
 * An input made of plain text. Two text inputs are equal when their text is equal.
 *
 * @param value the text the decision reasons over
 */
@ApiStatus.Experimental
class TextDecisionInput(
    /** The text the decision reasons over. */
    val value: String,
) : DecisionInput {

    /** Always [DecisionInput.TEXT]. */
    override val kind: String
        get() = DecisionInput.TEXT

    override fun equals(other: Any?): Boolean =
        this === other || other is TextDecisionInput && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "TextDecisionInput(value=$value)"
}

/**
 * An input made of a JSON object or array.
 *
 * The input keeps its own copy of the node, so changing the caller's node afterwards has no effect
 * on it. Two JSON inputs are equal when their nodes are equal.
 *
 * @param value the JSON object or array the decision reasons over
 * @throws IllegalArgumentException if the node is neither an object nor an array
 */
@ApiStatus.Experimental
class JsonDecisionInput(value: JsonNode) : DecisionInput {

    private val node: JsonNode = run {
        require(value.isObject || value.isArray) {
            "A JSON decision input must be an object or an array, but got ${value.nodeType}"
        }
        value.deepCopy()
    }

    /** Always [DecisionInput.JSON]. */
    override val kind: String
        get() = DecisionInput.JSON

    /**
     * The JSON the decision reasons over. Each read hands back a fresh copy, so changing it leaves
     * this input as it was.
     */
    val value: JsonNode
        get() = node.deepCopy()

    /**
     * Returns the JSON as compact text, with object members in the order they were given.
     *
     * @return the compact JSON text
     */
    fun toText(): String = node.toString()

    override fun equals(other: Any?): Boolean =
        this === other || other is JsonDecisionInput && node == other.node

    override fun hashCode(): Int = node.hashCode()

    override fun toString(): String = "JsonDecisionInput(value=${toText()})"
}
