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

import com.embabel.agent.core.Action
import com.embabel.agent.core.ActionQos
import com.embabel.agent.spi.config.spring.AgentPlatformProperties
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Unit tests for [withEffectiveQos].
 *
 * Uses MockK to stub [Action] rather than an anonymous object implementation,
 * because [Action] extends a wide interface hierarchy ([com.embabel.agent.core.DataFlowStep],
 * [com.embabel.plan.common.condition.ConditionAction], [com.embabel.agent.core.ActionRunner],
 * [com.embabel.agent.core.DataDictionary], [com.embabel.agent.core.ToolGroupConsumer]) and a
 * manual stub risks compile failures from missing methods, which would produce 0% coverage.
 *
 * Every branch of [withEffectiveQos] is covered without a Spring context.
 */
class ActionQosExtensionsTest {

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun actionWithQos(qos: ActionQos): Action = mockk<Action>(relaxed = true) {
        every { this@mockk.qos } returns qos
        every { name } returns "test-action"
        every { canRerun } returns false
    }

    /** Properties with no overrides set (all-null default). */
    private fun emptyProperties(): AgentPlatformProperties.ActionQosProperties =
        AgentPlatformProperties.ActionQosProperties()

    /** Properties with specific field overrides. */
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
    // No platform override configured
    // ---------------------------------------------------------------------------

    @Nested
    inner class `No platform override configured` {

        @Test
        fun `returns same instance when action has default qos and platform has no override`() {
            // Both resolve to ActionQos() — nothing to do, no allocation.
            val action = actionWithQos(ActionQos())
            val result = action.withEffectiveQos(emptyProperties())

            assertSame(action, result, "Expected the original action to be returned unchanged")
        }

        @Test
        fun `returns same instance when action has explicit qos and platform has no override`() {
            val action = actionWithQos(ActionQos(maxAttempts = 3))
            val result = action.withEffectiveQos(emptyProperties())

            assertSame(action, result)
        }
    }

    // ---------------------------------------------------------------------------
    // Platform override is configured
    // ---------------------------------------------------------------------------

    @Nested
    inner class `Platform override is configured` {

        @Test
        fun `applies platform maxAttempts to action with default qos`() {
            val action = actionWithQos(ActionQos())
            val result = action.withEffectiveQos(propertiesWith(maxAttempts = 2))

            assertEquals(2, result.qos.maxAttempts,
                "Platform maxAttempts=2 should override the default ActionQos(maxAttempts=5)")
        }

        @Test
        fun `applies all platform fields to action with default qos`() {
            val action = actionWithQos(ActionQos())
            val result = action.withEffectiveQos(
                propertiesWith(
                    maxAttempts = 2,
                    backoffMillis = 500L,
                    backoffMultiplier = 2.0,
                    backoffMaxInterval = 30_000L,
                    idempotent = true,
                )
            )

            assertEquals(2, result.qos.maxAttempts)
            assertEquals(500L, result.qos.backoffMillis)
            assertEquals(2.0, result.qos.backoffMultiplier)
            assertEquals(30_000L, result.qos.backoffMaxInterval)
            assertEquals(true, result.qos.idempotent)
        }

        @Test
        fun `respects explicit action qos even when platform has an override`() {
            // Explicit QoS (set via @Action / @Agent / DSL qos = ActionQos(...)) must win.
            val action = actionWithQos(ActionQos(maxAttempts = 7))
            val result = action.withEffectiveQos(propertiesWith(maxAttempts = 2))

            assertSame(action, result,
                "Explicitly configured qos must not be replaced by platform defaults")
            assertEquals(7, result.qos.maxAttempts)
        }

        @Test
        fun `partial platform override merges with ActionQos defaults for unset fields`() {
            // Platform sets only maxAttempts; other fields fall back to ActionQos() defaults.
            val action = actionWithQos(ActionQos())
            val result = action.withEffectiveQos(propertiesWith(maxAttempts = 1))

            assertEquals(1, result.qos.maxAttempts,
                "Platform-configured maxAttempts must be applied")
            assertEquals(ActionQos().backoffMillis, result.qos.backoffMillis,
                "Unset platform field must fall back to ActionQos default")
            assertEquals(ActionQos().backoffMultiplier, result.qos.backoffMultiplier,
                "Unset platform field must fall back to ActionQos default")
            assertEquals(ActionQos().idempotent, result.qos.idempotent,
                "Unset platform field must fall back to ActionQos default")
        }
    }

    // ---------------------------------------------------------------------------
    // Kotlin interface delegation
    // ---------------------------------------------------------------------------

    @Nested
    inner class `Kotlin delegation behaviour` {

        @Test
        fun `wrapped action delegates name correctly`() {
            val action = actionWithQos(ActionQos())
            val result = action.withEffectiveQos(propertiesWith(maxAttempts = 1))

            // The QosOverridingAction wrapper must forward all members except qos.
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
