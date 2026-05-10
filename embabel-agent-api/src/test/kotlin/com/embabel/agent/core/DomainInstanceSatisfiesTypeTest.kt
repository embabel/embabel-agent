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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the [DomainInstance] path through [satisfiesType]. Without this, a
 * value carrying a YAML-declared `DynamicType` only ever satisfies its
 * carrying JVM class — so a pack-declared type like `HubSpotContactCreated`
 * would never trigger an action whose input is the pack-declared type,
 * even though the platform knows about the type through its data
 * dictionary.
 *
 * The existing JVM-hierarchy match is covered by older blackboard tests
 * — this file adds NEW coverage for the DomainType-aware branch only.
 */
class DomainInstanceSatisfiesTypeTest {

    /** A generic carrier — the JVM class is `Carrier`; type identity comes from [domainType]. */
    private class Carrier(
        override val domainType: DomainType,
        override val properties: Map<String, Any?> = emptyMap(),
    ) : DomainInstance

    private fun dynamicType(name: String, parents: List<DomainType> = emptyList()): DynamicType =
        DynamicType(name = name, parents = parents)

    @Test
    fun `value with a DomainType satisfies its declared type name`() {
        val type = dynamicType("HubSpotContactCreated")
        assertTrue(satisfiesType(Carrier(type), "HubSpotContactCreated"))
    }

    @Test
    fun `value with a DomainType satisfies its parent type name`() {
        val parent = dynamicType("Signal")
        val child = dynamicType("HubSpotContactCreated", parents = listOf(parent))
        assertTrue(satisfiesType(Carrier(child), "Signal"))
    }

    @Test
    fun `value with a DomainType satisfies a transitive ancestor`() {
        val grandparent = dynamicType("Event")
        val parent = dynamicType("Signal", parents = listOf(grandparent))
        val child = dynamicType("HubSpotContactCreated", parents = listOf(parent))
        assertTrue(satisfiesType(Carrier(child), "Event"))
    }

    @Test
    fun `value with a DomainType does not satisfy an unrelated type`() {
        val type = dynamicType("HubSpotContactCreated")
        assertFalse(satisfiesType(Carrier(type), "StripeChargeFailed"))
    }

    @Test
    fun `JVM-class match still works for ordinary values`() {
        // Regression — the new branch must not change existing behavior.
        assertTrue(satisfiesType("any string", "String"))
    }

    @Test
    fun `JVM-class match takes precedence and does not require DomainInstance`() {
        // A non-DomainInstance value resolves entirely via JVM hierarchy.
        val list = listOf(1, 2, 3)
        assertTrue(satisfiesType(list, "List"))
        assertFalse(satisfiesType(list, "HubSpotContactCreated"))
    }

    @Test
    fun `value matches its DomainType ownLabel and FQN`() {
        // ownLabel = simple-name (capitalized) of the type's `name`.
        val type = dynamicType("com.acme.hubspot.HubSpotContactCreated")
        assertTrue(satisfiesType(Carrier(type), "HubSpotContactCreated"))
        assertTrue(satisfiesType(Carrier(type), "com.acme.hubspot.HubSpotContactCreated"))
    }

    @Test
    fun `properties are reachable via the interface, not the carrier`() {
        // The whole point of putting properties on the interface — uniform
        // property access without downcasting to whatever carrier class
        // an integration happens to use.
        val type = dynamicType("HubSpotContactCreated")
        val instance: DomainInstance = Carrier(type, mapOf("email" to "rod@example.com", "portalId" to 12345))

        assertEquals("rod@example.com", instance.properties["email"])
        assertEquals(12345, instance.properties["portalId"])
        assertEquals("HubSpotContactCreated", instance.domainType.name)
    }
}
