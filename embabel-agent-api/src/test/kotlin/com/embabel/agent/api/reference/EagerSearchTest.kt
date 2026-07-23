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
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Verifies the [EagerSearch] text overload, including request defaults and boundary values. */
class EagerSearchTest {

    @Nested
    inner class DefaultWithEagerSearchAbout {

        @Test
        fun `string overload builds request with zero threshold and given topK`() {
            val capturedRequest = slot<TextSimilaritySearchRequest>()
            val search = mockk<TestEagerSearch>()
            every { search.withEagerSearchAbout(capture(capturedRequest)) } returns search
            every { search.withEagerSearchAbout(any<String>(), any()) } answers { callOriginal() }

            val result = search.withEagerSearchAbout("find related APIs", limit = 5)

            assertSame(search, result)
            assertEquals("find related APIs", capturedRequest.captured.query)
            assertEquals(0.0, capturedRequest.captured.similarityThreshold)
            assertEquals(5, capturedRequest.captured.topK)
        }

        @Test
        fun `string overload preserves boundary inputs without coercion`() {
            val capturedRequest = slot<TextSimilaritySearchRequest>()
            val search = mockk<TestEagerSearch>()
            every { search.withEagerSearchAbout(capture(capturedRequest)) } returns search
            every { search.withEagerSearchAbout(any<String>(), any()) } answers { callOriginal() }

            search.withEagerSearchAbout("", limit = 0)

            assertEquals("", capturedRequest.captured.query)
            assertEquals(0.0, capturedRequest.captured.similarityThreshold)
            assertEquals(0, capturedRequest.captured.topK)
        }
    }

    private interface TestEagerSearch : EagerSearch<TestEagerSearch>
}
