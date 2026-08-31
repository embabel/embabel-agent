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

import com.embabel.common.util.EmbabelObjectMapperHolder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration

class DelayPolicyTest {

    @Nested
    inner class None {

        @Test
        fun `has zero duration`() {
            assertEquals(Duration.ZERO, DelayPolicy.None.duration)
        }

        @Test
        fun `is singleton`() {
            assertSame(DelayPolicy.None, DelayPolicy.None)
        }
    }

    @Nested
    inner class Fixed {

        @Test
        fun `holds given duration`() {
            val policy = DelayPolicy.Fixed(5000L)
            assertEquals(Duration.ofSeconds(5), policy.duration)
        }
    }

    @Nested
    inner class OfDelay {

        @Test
        fun `NONE maps to None`() {
            assertSame(DelayPolicy.None, DelayPolicy.of(Delay.NONE))
        }

        @Test
        fun `MEDIUM maps to 400ms`() {
            assertEquals(Duration.ofMillis(400), DelayPolicy.of(Delay.MEDIUM).duration)
        }

        @Test
        fun `LONG maps to 2 seconds`() {
            assertEquals(Duration.ofSeconds(2), DelayPolicy.of(Delay.LONG).duration)
        }
    }

    @Nested
    inner class OfMillis {

        @Test
        fun `positive millis produces Fixed`() {
            assertEquals(Duration.ofMillis(1500), DelayPolicy.of(1500L).duration)
        }

        @Test
        fun `zero millis produces Fixed(0) not None`() {
            val policy = DelayPolicy.of(0L)
            assertInstanceOf(DelayPolicy.Fixed::class.java, policy)
            assertEquals(Duration.ZERO, policy.duration)
        }

        @Test
        fun `negative millis is clamped to Fixed(0) not None`() {
            val policy = DelayPolicy.of(-1L)
            assertInstanceOf(DelayPolicy.Fixed::class.java, policy)
            assertEquals(Duration.ZERO, policy.duration)
        }
    }

    @Nested
    inner class Serialization {

        private val mapper = EmbabelObjectMapperHolder.createDefault().get()

        @Test
        fun `None serializes to 0`() {
            assertEquals("0", mapper.writeValueAsString(DelayPolicy.None))
        }

        @Test
        fun `Fixed serializes to millis`() {
            assertEquals("400", mapper.writeValueAsString(DelayPolicy.Fixed(400L)))
        }

        @Test
        fun `0 deserializes to Fixed with zero duration`() {
            val policy = mapper.readValue("0", DelayPolicy::class.java)
            assertInstanceOf(DelayPolicy.Fixed::class.java, policy)
            assertEquals(Duration.ZERO, policy.duration)
        }

        @Test
        fun `positive millis deserializes to Fixed`() {
            val policy = mapper.readValue("2000", DelayPolicy::class.java)
            assertEquals(Duration.ofSeconds(2), policy.duration)
        }

        @Test
        fun `round-trip Fixed`() {
            val original = DelayPolicy.Fixed(1500L)
            val json = mapper.writeValueAsString(original)
            val restored = mapper.readValue(json, DelayPolicy::class.java)
            assertEquals(original.duration, restored.duration)
        }
    }
}
