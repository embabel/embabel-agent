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

import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import org.jetbrains.annotations.ApiStatus
import tools.jackson.databind.annotation.JsonDeserialize

/** Pure metadata for the decision family, which also supports classification. */
@ApiStatus.Experimental
@JsonDeserialize(`as` = DecisionServiceMetadataImpl::class)
interface DecisionServiceMetadata : ClassificationServiceMetadata {
    override val type: ModelType get() = ModelType.DECISION

    companion object {
        /** Create serializable metadata without retaining a live decision service. */
        @JvmStatic
        fun create(name: String, provider: String): DecisionServiceMetadata = DecisionServiceMetadataImpl(name, provider)
    }
}

/** Classification plus proposition assessment. Applications own any routing or revision policy. */
@ApiStatus.Experimental
interface DecisionService : ClassificationService, DecisionServiceMetadata {
    /** Family remains DECISION even when this service is used only for classification. */
    override val type: ModelType get() = ModelType.DECISION

    /** Assess the proposition; a false answer is successful evidence, not an operational failure. */
    fun assess(request: PropositionRequest): PropositionResult

    /** Pure DECISION metadata snapshot; never serialize a live service in place of this value. */
    override fun metadata(): DecisionServiceMetadata = DecisionServiceMetadata.create(name, provider)
}

private data class DecisionServiceMetadataImpl(
    override val name: String,
    override val provider: String,
) : DecisionServiceMetadata
