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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

class DecisionModelMetadataTest {

    @Test
    fun `decision metadata is detached pure data with its own model type`() {
        val metadata: ModelMetadata = DecisionModelMetadata("proposition-revision", "typesafe")

        assertThat(metadata.name).isEqualTo("proposition-revision")
        assertThat(metadata.provider).isEqualTo("typesafe")
        assertThat(metadata.type).isEqualTo(ModelType.DECISION)
    }

    @Test
    fun `decision metadata participates in polymorphic serialization`() {
        val mapper = jacksonObjectMapper()
        val modelMetadataList = mapper.typeFactory.constructCollectionType(List::class.java, ModelMetadata::class.java)
        val json = mapper.writerFor(modelMetadataList)
            .writeValueAsString(listOf(DecisionModelMetadata("proposition-revision", "typesafe")))

        assertThat(json).contains(DecisionModelMetadata::class.qualifiedName)
        assertThat(json).contains("proposition-revision", "typesafe", "DECISION")
    }
}
