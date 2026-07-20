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
package com.embabel.agent.api.reference

import com.embabel.common.core.types.TextSimilaritySearchRequest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class EagerSearchTest {

    @Nested
    inner class DefaultWithEagerSearchAbout {

        @Test
        fun `string overload builds request with zero threshold and given topK`() {
            val stub = RecordingEagerSearch()

            val result = stub.withEagerSearchAbout("find related APIs", limit = 5)

            assertSame(stub, result)
            val request = checkNotNull(stub.lastRequest)
            assertEquals("find related APIs", request.query)
            assertEquals(0.0, request.similarityThreshold)
            assertEquals(5, request.topK)
        }

        @Test
        fun `string overload preserves boundary inputs without coercion`() {
            val stub = RecordingEagerSearch()

            stub.withEagerSearchAbout("", limit = 0)

            val request = checkNotNull(stub.lastRequest)
            assertEquals("", request.query)
            assertEquals(0.0, request.similarityThreshold)
            assertEquals(0, request.topK)
        }
    }

    private class RecordingEagerSearch : EagerSearch<RecordingEagerSearch> {
        var lastRequest: TextSimilaritySearchRequest? = null

        override val name: String = "recording"
        override val description: String = "test stub"

        override fun withEagerSearchAbout(request: TextSimilaritySearchRequest): RecordingEagerSearch {
            lastRequest = request
            return this
        }

        override fun notes(): String = ""
    }
}
