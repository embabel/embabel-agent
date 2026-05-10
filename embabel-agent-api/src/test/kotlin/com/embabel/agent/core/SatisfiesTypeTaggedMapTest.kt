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
package com.embabel.agent.core

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the tagged-map path through [satisfiesType] — a `Map<String, Any?>`
 * with [TYPE_NAME_KEY] (and optionally [TYPE_LABELS_KEY]) participates in
 * type-aware matching without a carrier class. Useful for pack-authored
 * tools and sandbox script returns whose payload is plain JSON-shaped data.
 */
class SatisfiesTypeTaggedMapTest {

    @Test
    fun `tagged map matches its own type name`() {
        val payload = mapOf(
            TYPE_NAME_KEY to "HubSpotContactCreated",
            "email" to "rod@example.com",
        )
        assertTrue(satisfiesType(payload, "HubSpotContactCreated"))
    }

    @Test
    fun `tagged map matches a parent listed in TYPE_LABELS_KEY`() {
        val payload = mapOf(
            TYPE_NAME_KEY to "HubSpotContactCreated",
            TYPE_LABELS_KEY to listOf("HubSpotContactCreated", "Signal", "Event"),
            "email" to "rod@example.com",
        )
        assertTrue(satisfiesType(payload, "Signal"))
        assertTrue(satisfiesType(payload, "Event"))
    }

    @Test
    fun `tagged map without TYPE_LABELS_KEY only satisfies its own type`() {
        // Emitter that doesn't know its parent chain. Self-name still works;
        // ancestor names do not.
        val payload = mapOf(
            TYPE_NAME_KEY to "HubSpotContactCreated",
            "email" to "rod@example.com",
        )
        assertTrue(satisfiesType(payload, "HubSpotContactCreated"))
        assertFalse(satisfiesType(payload, "Signal"))
    }

    @Test
    fun `tagged map does not satisfy unrelated types`() {
        val payload = mapOf(
            TYPE_NAME_KEY to "HubSpotContactCreated",
            TYPE_LABELS_KEY to listOf("HubSpotContactCreated", "Signal"),
        )
        assertFalse(satisfiesType(payload, "StripeChargeFailed"))
    }

    @Test
    fun `untagged map does not match by content`() {
        // Plain map with no _typeName must NOT match a type — otherwise
        // every map would silently satisfy every condition.
        val payload = mapOf("email" to "rod@example.com")
        assertFalse(satisfiesType(payload, "HubSpotContactCreated"))
        assertFalse(satisfiesType(payload, "Signal"))
    }

    @Test
    fun `TYPE_LABELS_KEY accepts any Iterable not just List`() {
        val payload = mapOf(
            TYPE_NAME_KEY to "HubSpotContactCreated",
            TYPE_LABELS_KEY to setOf("HubSpotContactCreated", "Signal"),
        )
        assertTrue(satisfiesType(payload, "Signal"))
    }

    @Test
    fun `non-string TYPE_NAME_KEY does not crash and does not match`() {
        // Defensive — emitter mistake shouldn't break the planner.
        val payload = mapOf(TYPE_NAME_KEY to 42, "email" to "rod@example.com")
        assertFalse(satisfiesType(payload, "HubSpotContactCreated"))
    }

    @Test
    fun `JVM Map class match still works`() {
        // Regression — a tagged map should still answer to "Map" via the
        // JVM-hierarchy branch, since its JVM class is a Map subtype.
        val payload = mapOf(TYPE_NAME_KEY to "HubSpotContactCreated")
        assertTrue(satisfiesType(payload, "Map"))
    }
}
