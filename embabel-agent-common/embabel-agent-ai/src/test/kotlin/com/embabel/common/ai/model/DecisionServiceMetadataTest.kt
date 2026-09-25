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

import com.embabel.common.ai.classification.*
import com.embabel.common.ai.decision.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

class DecisionServiceMetadataTest {
    private class HazardousDecision : DecisionService {
        override val name = "decision-model"
        override val provider = "provider"
        val credential: String get() = error("secret getter must never run")
        val client: Any get() = error("client getter must never run")
        override fun classify(request: ClassificationRequest) = ClassificationResult.NoMatch(ModelProvenance(name, provider))
        override fun assess(request: PropositionRequest) = PropositionResult.Answered(false, ModelProvenance(name, provider))
    }

    @Test
    fun `decision capability retains its model family and safe snapshot through classifier view`() {
        val service: ClassificationService = HazardousDecision()
        assertEquals(ModelType.DECISION, service.type)
        val snapshot = service.metadata()
        assertTrue(snapshot is DecisionServiceMetadata)
        assertFalse(snapshot is ClassificationService)
        val mapper = jacksonObjectMapper()
        val json = mapper.writerFor(ModelMetadata::class.java).writeValueAsString(snapshot)
        assertFalse(json.contains("HazardousDecision"))
        assertFalse(json.contains("credential"))
        assertFalse(json.contains("client"))
        val tree = mapper.readTree(json)
        assertEquals(setOf("@class", "name", "provider", "type"), tree.propertyNames().toSet())
        val restored = mapper.readValue(json, ModelMetadata::class.java)
        assertTrue(restored is DecisionServiceMetadata)
        assertEquals(ModelType.DECISION, restored.type)
        assertEquals(service.name, restored.name)
        assertEquals(service.provider, restored.provider)
    }

    @Test
    fun `classification snapshot and static factories round trip as pure metadata`() {
        val service = object : ClassificationService {
            override val name = "classifier"
            override val provider = "provider"
            val credential: String get() = error("secret getter must never run")
            override fun classify(request: ClassificationRequest) = ClassificationResult.NoMatch(ModelProvenance(name, provider))
        }
        val snapshots = listOf(service.metadata(), ClassificationServiceMetadata.create("classifier", "provider"),
            DecisionServiceMetadata.create("decision", "provider"))
        val mapper = jacksonObjectMapper()
        snapshots.forEach {
            val json = mapper.writerFor(ModelMetadata::class.java).writeValueAsString(it)
            val restored = mapper.readValue(json, ModelMetadata::class.java)
            assertEquals(it.type, restored.type)
            assertEquals(it.name, restored.name)
            assertEquals(it.provider, restored.provider)
            assertFalse(restored is ClassificationService)
        }
        assertEquals("    name: classifier, provider: provider", service.infoString(false, 2))
    }
}
