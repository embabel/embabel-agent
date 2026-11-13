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
package com.embabel.agent.a2a.server

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class A2AProtocolVersionTest {

    @Nested
    inner class FromHeader {

        @Test
        fun `missing or blank header is A2A 0_3`() {
            assertEquals(A2AProtocolVersion.V0_3, A2AProtocolVersion.fromHeader(null))
            assertEquals(A2AProtocolVersion.V0_3, A2AProtocolVersion.fromHeader(" "))
        }

        @Test
        fun `supported major and minor versions are served`() {
            assertEquals(A2AProtocolVersion.V1_0, A2AProtocolVersion.fromHeader("1.0"))
            assertEquals(A2AProtocolVersion.V0_3, A2AProtocolVersion.fromHeader("0.3"))
        }

        @Test
        fun `patch version is ignored`() {
            assertEquals(A2AProtocolVersion.V1_0, A2AProtocolVersion.fromHeader("1.0.2"))
            assertEquals(A2AProtocolVersion.V0_3, A2AProtocolVersion.fromHeader("0.3.0"))
        }

        @Test
        fun `another minor version of a supported major version is not supported`() {
            assertNull(A2AProtocolVersion.fromHeader("1.1"))
            assertNull(A2AProtocolVersion.fromHeader("0.2"))
        }

        @Test
        fun `version without minor version is not supported`() {
            assertNull(A2AProtocolVersion.fromHeader("1"))
        }

        @Test
        fun `unknown versions are not supported`() {
            assertNull(A2AProtocolVersion.fromHeader("2.0"))
            assertNull(A2AProtocolVersion.fromHeader("1.x"))
            assertNull(A2AProtocolVersion.fromHeader("latest"))
        }
    }
}
