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
import io.mockk.verify
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Verifies the [EagerSearch] text overload, including request defaults and forwarded inputs. */
class EagerSearchTest {

    private fun mockEagerSearch(): TestEagerSearch {
        val search = mockk<TestEagerSearch>()
        every { search.withEagerSearchAbout(any<TextSimilaritySearchRequest>()) } returns search
        every { search.withEagerSearchAbout(any<String>(), any()) } answers { callOriginal() }
        return search
    }

    @Nested
    inner class DefaultWithEagerSearchAbout {

        @Test
        fun `string overload builds request with zero threshold and given topK`() {
            val capturedRequest = slot<TextSimilaritySearchRequest>()
            val search = mockEagerSearch()

            val result = search.withEagerSearchAbout("find related APIs", limit = 5)

            assertSame(search, result)
            verify(exactly = 1) { search.withEagerSearchAbout(capture(capturedRequest)) }
            assertEquals("find related APIs", capturedRequest.captured.query)
            assertEquals(0.0, capturedRequest.captured.similarityThreshold)
            assertEquals(5, capturedRequest.captured.topK)
        }

        @Test
        fun `string overload forwards empty query and zero limit`() {
            val capturedRequest = slot<TextSimilaritySearchRequest>()
            val search = mockEagerSearch()

            search.withEagerSearchAbout("", limit = 0)

            verify(exactly = 1) { search.withEagerSearchAbout(capture(capturedRequest)) }
            assertEquals("", capturedRequest.captured.query)
            assertEquals(0.0, capturedRequest.captured.similarityThreshold)
            assertEquals(0, capturedRequest.captured.topK)
        }
    }

    private interface TestEagerSearch : EagerSearch<TestEagerSearch>
}
