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
package com.embabel.agent.test.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Unit tests for the data fixtures defined in [simplyMagic.kt].
 */
class SimplyMagicTest {

    @Test
    fun `magic victim preserves its name and supports copy`() {
        // Arrange
        val victim = MagicVictim("Hamish")

        // Act
        val copiedVictim = victim.copy(name = "Marmaduke")

        // Assert
        assertEquals("Hamish", victim.name)
        assertEquals("Marmaduke", copiedVictim.name)
        assertFalse(victim == copiedVictim)
    }

    @Test
    fun `frog preserves its name and supports equality`() {
        // Arrange
        val frog = Frog("Kermit")

        // Act
        val sameFrog = Frog("Kermit")
        val differentFrog = Frog("Freddo")

        // Assert
        assertEquals("Kermit", frog.name)
        assertEquals(sameFrog, frog)
        assertFalse(frog == differentFrog)
    }
}
