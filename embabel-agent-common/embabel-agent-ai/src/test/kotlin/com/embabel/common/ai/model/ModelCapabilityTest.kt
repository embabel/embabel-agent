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
package com.embabel.common.ai.model

import com.embabel.common.ai.classification.ClassificationService
import com.embabel.common.ai.decision.DecisionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelCapabilityTest {

    @Test
    fun `the model type enum keeps the four values it has today`() {
        // A new kind of model is a new ModelCapability interface. If this test fails, the change
        // added a ModelType value instead. See https://github.com/embabel/embabel-agent/issues/1585.
        assertEquals(
            listOf(ModelType.LLM, ModelType.EMBEDDING, ModelType.CLASSIFICATION, ModelType.DECISION),
            ModelType.entries.toList(),
        )
    }

    @Test
    fun `embedding, classification and decision services are model capabilities`() {
        listOf(EmbeddingService::class, ClassificationService::class, DecisionService::class).forEach { service ->
            assertTrue(
                ModelCapability::class.java.isAssignableFrom(service.java),
                "${service.simpleName} should extend ModelCapability",
            )
        }
    }
}
