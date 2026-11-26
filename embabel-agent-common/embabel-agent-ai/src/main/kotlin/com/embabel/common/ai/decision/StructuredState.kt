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

import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

// Reads decimals back exactly, so a snapshot keeps the digits the caller gave.
private val snapshotMapper: JsonMapper = JsonMapper.builder()
    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
    .build()

/**
 * Checks that a structured state is an object or array, then writes it out and reads it back.
 * That leaves a tree of plain JSON values that shares nothing with the caller's node.
 *
 * @param state the caller's node
 * @return the request's own tree
 * @throws IllegalArgumentException if the state is not a JSON object or array, or cannot be
 * written as JSON
 */
internal fun structuredSnapshot(state: JsonNode): JsonNode {
    require(state.isObject || state.isArray) {
        "A structured decision input must be a JSON object or array, but was ${state.nodeType}. " +
            "Pass text as a String."
    }
    return try {
        snapshotMapper.readTree(snapshotMapper.writeValueAsString(state))
    } catch (_: JacksonException) {
        // The writer's message can quote the state, so it is dropped.
        throw IllegalArgumentException(
            "A structured decision input could not be written as JSON. Build it from plain JSON values.",
        )
    }
}
