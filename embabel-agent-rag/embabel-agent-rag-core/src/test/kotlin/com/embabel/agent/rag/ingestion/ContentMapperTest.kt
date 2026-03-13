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
package com.embabel.agent.rag.ingestion

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.URI

class ContentMapperTest {

    private val testUri = URI("https://example.com/test")

    @Nested
    inner class Identity {

        @Test
        fun `identity mapper returns content unchanged`() {
            val input = "hello world".toByteArray()
            val result = ContentMapper.IDENTITY.map(input, testUri)

            assertArrayEquals(input, result)
        }
    }

    @Nested
    inner class Composition {

        @Test
        fun `then composes two mappers in order`() {
            val appendA = ContentMapper { content, _ -> content + "A".toByteArray() }
            val appendB = ContentMapper { content, _ -> content + "B".toByteArray() }
            val composed = appendA.then(appendB)

            val result = String(composed.map("X".toByteArray(), testUri))

            assertEquals("XAB", result)
        }

        @Test
        fun `composing with identity is a no-op`() {
            val appendA = ContentMapper { content, _ -> content + "A".toByteArray() }
            val composed = appendA.then(ContentMapper.IDENTITY)

            val result = String(composed.map("X".toByteArray(), testUri))

            assertEquals("XA", result)
        }

        @Test
        fun `three mappers compose correctly`() {
            val upper = ContentMapper { content, _ -> String(content).uppercase().toByteArray() }
            val addPrefix = ContentMapper { content, _ -> ("PREFIX:".toByteArray() + content) }
            val addSuffix = ContentMapper { content, _ -> (content + ":SUFFIX".toByteArray()) }

            val pipeline = upper.then(addPrefix).then(addSuffix)
            val result = String(pipeline.map("hello".toByteArray(), testUri))

            assertEquals("PREFIX:HELLO:SUFFIX", result)
        }
    }
}
