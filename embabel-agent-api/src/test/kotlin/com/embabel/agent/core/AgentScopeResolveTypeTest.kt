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
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

/**
 * Pins the simple-name fallback in [AgentScope.resolveType] — the lookup
 * accepts both the FQN of a registered type AND its simple name. Without
 * this, action YAML specs that use the natural simple-name form for
 * `outputTypeName` (e.g. `BackgroundMessage`) fail at execution time
 * because the platform registers JVM types under their FQN
 * (`com.embabel.agent.spec.model.BackgroundMessage`).
 */
class AgentScopeResolveTypeTest {

    private val backgroundMessageJvm = JvmType(java.util.LinkedHashMap::class.java)

    private fun scopeWith(vararg domainTypes: DomainType): AgentScope =
        AgentScope(
            name = "test-scope",
            description = "test",
            referenceTypes = domainTypes.toList(),
        )

    @Test
    fun `resolveType matches by exact FQN`() {
        val type = JvmType(java.util.HashMap::class.java)
        val scope = scopeWith(type)

        assertEquals(type, scope.resolveType(java.util.HashMap::class.java.name))
    }

    @Test
    fun `resolveType falls back to simple-name match when FQN is not used`() {
        // Pins the bug fix: action YAML can declare outputTypeName as
        // simple name `HashMap` and the lookup still succeeds even though
        // the registered type carries the FQN `java.util.HashMap`.
        val type = JvmType(java.util.HashMap::class.java)
        val scope = scopeWith(type)

        assertEquals(type, scope.resolveType("HashMap"))
    }

    @Test
    fun `resolveType still works for DynamicType registered by simple name`() {
        // DynamicTypes from YAML declare a simple `name` (not an FQN).
        // The simple-name path must continue to find them via the
        // exact-match branch.
        val type = DynamicType(name = "HubSpotContactCreated")
        val scope = scopeWith(type)

        assertEquals(type, scope.resolveType("HubSpotContactCreated"))
    }

    @Test
    fun `exact match wins over simple-name fallback`() {
        // If both `com.acme.Foo` and `Foo` (a DynamicType with that
        // exact simple name) are registered, `resolveType("Foo")` must
        // return the exact `Foo` not the FQN one. Otherwise simple-name
        // lookups become non-deterministic when packages collide with
        // a simple-name DynamicType.
        val fqnType = JvmType(Class.forName("java.util.HashMap"))
        val simpleNameType = DynamicType(name = "HashMap")
        val scope = scopeWith(fqnType, simpleNameType)

        assertEquals(simpleNameType, scope.resolveType("HashMap"))
    }

    @Test
    fun `unknown type still throws with the same diagnostic shape`() {
        val scope = scopeWith(JvmType(java.util.HashMap::class.java))

        val ex = assertThrows<IllegalStateException> {
            scope.resolveType("DoesNotExist")
        }
        // Message preserves the existing user-facing format.
        assert(ex.message!!.contains("Schema type 'DoesNotExist' not found"))
        assert(ex.message!!.contains("test-scope"))
    }
}
