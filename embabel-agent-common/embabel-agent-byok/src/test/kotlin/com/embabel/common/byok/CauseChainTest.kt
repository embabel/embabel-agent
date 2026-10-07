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
package com.embabel.common.byok

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.io.IOException

class CauseChainTest {

    @Test
    fun `the exception itself is returned when it has the type`() {
        val failure = IOException("refused")

        assertSame(failure, failure.firstOfType<IOException>())
    }

    @Test
    fun `a cause two levels down is returned when it has the type`() {
        val io = IOException("refused")
        val failure = RuntimeException("outer", IllegalStateException("middle", io))

        assertSame(io, failure.firstOfType<IOException>())
    }

    @Test
    fun `the nearest exception is returned when two have the type`() {
        val inner = IOException("inner")
        val nearest = IOException("nearest", inner)
        val failure = RuntimeException("outer", nearest)

        assertSame(nearest, failure.firstOfType<IOException>())
    }

    @Test
    fun `null is returned when no exception has the type`() {
        val failure = RuntimeException("outer", IllegalStateException("inner"))

        assertNull(failure.firstOfType<IOException>())
    }
}
