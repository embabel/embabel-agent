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

import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.core.types.HasInfoString
import com.embabel.common.util.indent
import org.jetbrains.annotations.ApiStatus
import tools.jackson.databind.annotation.JsonDeserialize

/** Pure metadata for a classification model, independent of its provider client. */
@ApiStatus.Experimental
@JsonDeserialize(`as` = ClassificationServiceMetadataImpl::class)
interface ClassificationServiceMetadata : ModelMetadata {
    override val type: ModelType get() = ModelType.CLASSIFICATION

    companion object {
        /** Create a pure metadata value suitable for serialization through [ModelMetadata]. */
        @JvmStatic
        fun create(name: String, provider: String): ClassificationServiceMetadata =
            ClassificationServiceMetadataImpl(name, provider)
    }
}

/** Provider-neutral closed-category classification; implementations own execution and provider integration. */
@ApiStatus.Experimental
interface ClassificationService : ClassificationServiceMetadata, HasInfoString {
    /** Classify text using only the request's category domain, returning evidence rather than routing policy. */
    fun classify(request: ClassificationRequest): ClassificationResult

    /**
     * Copy public model identity into pure metadata before serialization. This snapshot contains no
     * live client, credentials, or implementation discriminator. Serializing the live service itself
     * is outside this contract; use this method even when the service is viewed as [ModelMetadata].
     */
    fun metadata(): ClassificationServiceMetadata = ClassificationServiceMetadata.create(name, provider)

    override fun infoString(verbose: Boolean?, indent: Int): String =
        "name: $name, provider: $provider".indent(indent)
}

private data class ClassificationServiceMetadataImpl(
    override val name: String,
    override val provider: String,
) : ClassificationServiceMetadata
