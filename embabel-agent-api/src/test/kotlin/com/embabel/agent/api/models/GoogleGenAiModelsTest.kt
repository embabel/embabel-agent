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
package com.embabel.agent.api.models

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties

class GoogleGenAiModelsTest {

    @ParameterizedTest
    @ValueSource(strings = ["TEXT_EMBEDDING_004", "TEXT_EMBEDDING_005"])
    fun `deprecated embedding model should point to the default embedding model`(constantName: String) {
        val property = GoogleGenAiModels.Companion::class.memberProperties.single { it.name == constantName }
        val deprecated = property.findAnnotation<Deprecated>()

        assertNotNull(deprecated, "$constantName should be deprecated")
        assertEquals("GEMINI_EMBEDDING_001", deprecated!!.replaceWith.expression)
    }

    @Test
    fun `default embedding model should be gemini-embedding-001`() {
        assertEquals(GoogleGenAiModels.GEMINI_EMBEDDING_001, GoogleGenAiModels.DEFAULT_TEXT_EMBEDDING_MODEL)
    }
}
