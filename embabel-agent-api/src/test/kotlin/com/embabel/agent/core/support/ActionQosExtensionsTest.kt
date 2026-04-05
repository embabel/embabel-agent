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
package com.embabel.agent.core.support

import com.embabel.agent.core.ActionQos
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.core.ActionStatus
import com.embabel.agent.core.Action
import com.embabel.agent.core.IoBinding
import com.embabel.agent.core.DomainType
import com.embabel.agent.core.ToolGroupRequirement
import com.embabel.agent.spi.config.spring.AgentPlatformProperties
import com.embabel.plan.CostComputation
import com.embabel.plan.common.condition.EffectSpec
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Unit tests for [withEffectiveQos].
 *
 * Every test branch is covered without Spring context —
 * the extension function is pure logic over data classes.
 */
class ActionQosExtensionsTest {

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /** Minimal stub Action whose only meaningful property is qos. */
    private fun actionWithQos(qos: ActionQos): Action = object : Action {
        override val name = "test-action"
        override val description = "test"
        override val qos = qos
        override val canRerun = false
        override val inputs: Set<IoBinding> = emptySet()
        override val outputs: Set<IoBinding> = emptySet()
        override val toolGroups: Set<ToolGroupRequirement> = emptySet()
        override val cost: CostComputation = { 0.0 }
        override val value: CostComputation = { 0.0 }
        override val domainTypes: Collection<DomainType> = emptySet()
        override val preconditions: EffectSpec = emptyMap()
        override val effects: EffectSpec = emptyMap()
        override fun execute(processContext: ProcessContext): ActionStatus =
            throw UnsupportedOperationException()
        override fun referencedInputProperties(variable: String): Set<String> = emptySet()
        override fun infoString(verbose: Boolean?, indent: Int): String = name
    }

    /** Properties with no overrides set (all-null default). */
    private fun emptyProperties(): AgentPlatformProperties.ActionQosProperties =
        AgentPlatformProperties.ActionQosProperties()

    /** Properties with a specific maxAttempts default. */
    private fun propertiesWith(
        maxAttempts: Int? = null,
        backoffMillis: Long? = null,
        backoffMultiplier: Double? = null,
        backoffMaxInterval: Long? = null,
        idempotent: Boolean? = null,
    ): AgentPlatformProperties.ActionQosProperties =
        AgentPlatformProperties.ActionQosProperties().apply {
            default = AgentPlatformProperties.ActionQosProperties.ActionProperties(
                maxAttempts = maxAttempts,
                backoffMillis = backoffMillis,
                backoffMultiplier = backoffMultiplier,
                backoffMaxInterval = backoffMaxInterval,
                idempotent = idempotent,
            )
        }

    // ---------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------

    @Nested
    inner class `No platform override configured` {

        @Test
        fun `returns same instance when action has default qos and platform has no override`() {
            // Both resolve to ActionQos() — nothing to do, no allocation expected.
            val action = actionWithQos(ActionQos())
            val result = action.withEffectiveQos(emptyProperties())

            // Identity: no wrapping occurred
            assertSame(action, result, "Expected the original action to be returned unchanged")
        }

        @Test
        fun `returns same instance when action has explicit qos and platform has no override`() {
            val explicit = ActionQos(maxAttempts = 3)
            val action = actionWithQos(explicit)
            val result = action.withEffectiveQos(emptyProperties())

            assertSame(action, result)
        }
    }

    @Nested
    inner class `Platform override is configured` {

        @Test
        fun `applies platform maxAttempts to action with default qos`() {
            val action = actionWithQos(ActionQos())
            val props = propertiesWith(maxAttempts = 2)

            val result = action.withEffectiveQos(props)

            assertEquals(2, result.qos.maxAttempts,
                "Platform maxAttempts=2 should override the default ActionQos(maxAttempts=5)")
        }

        @Test
        fun `applies all platform fields to action with default qos`() {
            val action = actionWithQos(ActionQos())
            val props = propertiesWith(
                maxAttempts = 2,
                backoffMillis = 500L,
                backoffMultiplier = 2.0,
                backoffMaxInterval = 30_000L,
                idempotent = true,
            )

            val result = action.withEffectiveQos(props)

            assertEquals(2, result.qos.maxAttempts)
            assertEquals(500L, result.qos.backoffMillis)
            assertEquals(2.0, result.qos.backoffMultiplier)
            assertEquals(30_000L, result.qos.backoffMaxInterval)
            assertEquals(true, result.qos.idempotent)
        }

        @Test
        fun `respects explicit action qos even when platform has an override`() {
            // Action was explicitly configured via @Action / @Agent / DSL qos = ActionQos(...)
            val explicit = ActionQos(maxAttempts = 7)
            val action = actionWithQos(explicit)
            val props = propertiesWith(maxAttempts = 2)

            val result = action.withEffectiveQos(props)

            // Explicit wins — platform default must NOT override it.
            assertSame(action, result,
                "Explicit qos should be left unchanged by platform defaults")
            assertEquals(7, result.qos.maxAttempts)
        }

        @Test
        fun `partial platform override merges with ActionQos defaults for unset fields`() {
            // Platform sets only maxAttempts; all other fields remain at ActionQos() defaults.
            val action = actionWithQos(ActionQos())
            val props = propertiesWith(maxAttempts = 1)

            val result = action.withEffectiveQos(props)

            assertEquals(1, result.qos.maxAttempts,
                "Platform-configured maxAttempts should be applied")
            assertEquals(ActionQos().backoffMillis, result.qos.backoffMillis,
                "Unset platform field should fall back to ActionQos default")
            assertEquals(ActionQos().backoffMultiplier, result.qos.backoffMultiplier,
                "Unset platform field should fall back to ActionQos default")
            assertEquals(ActionQos().idempotent, result.qos.idempotent,
                "Unset platform field should fall back to ActionQos default")
        }
    }

    @Nested
    inner class `Kotlin delegation behaviour` {

        @Test
        fun `wrapped action delegates name correctly`() {
            val action = actionWithQos(ActionQos())
            val result = action.withEffectiveQos(propertiesWith(maxAttempts = 1))

            // The wrapper must forward everything except qos unchanged.
            assertEquals("test-action", result.name,
                "Kotlin by-delegation wrapper must forward name to delegate")
        }

        @Test
        fun `wrapped action delegates canRerun correctly`() {
            val action = actionWithQos(ActionQos())
            val result = action.withEffectiveQos(propertiesWith(maxAttempts = 1))

            assertEquals(false, result.canRerun,
                "Kotlin by-delegation wrapper must forward canRerun to delegate")
        }
    }
}
