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
package com.embabel.common.ai.decision

import com.embabel.common.ai.classification.Category
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

// The JSON forms of the classification and proposition types that appear inside decision JSON.
// Those types keep their own JSON everywhere else, so the decision types write and read these
// forms in their place.

// Every decision type and JSON form has a private any-setter that calls this. Jackson passes an
// unknown member to the any-setter whatever the mapper's FAIL_ON_UNKNOWN_PROPERTIES setting is.
internal fun rejectUnknownMember(type: String, name: String): Nothing =
    throw IllegalArgumentException("Unknown member '$name' in $type")

/** A choice option as JSON. */
@JsonPropertyOrder("id", "description")
internal class OptionJson @JsonCreator constructor(
    @JsonProperty("id", required = true) val id: String,
    @JsonProperty("description", required = true) val description: String,
) {
    constructor(category: Category) : this(category.id, category.description)

    fun toCategory(): Category = Category(id, description)

    @JsonAnySetter
    private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("Category", name)
}

/** Model provenance as JSON. Absent version and request id are left out. */
@JsonPropertyOrder("modelName", "provider", "version", "requestId")
@JsonInclude(JsonInclude.Include.NON_NULL)
internal class ProvenanceJson @JsonCreator constructor(
    @JsonProperty("modelName", required = true) val modelName: String,
    @JsonProperty("provider", required = true) val provider: String,
    @JsonProperty("version") val version: String?,
    @JsonProperty("requestId") val requestId: String?,
) {
    constructor(provenance: ModelProvenance) :
        this(provenance.modelName, provenance.provider, provenance.version, provenance.requestId)

    fun toProvenance(): ModelProvenance = ModelProvenance(modelName, provider, version, requestId)

    @JsonAnySetter
    private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("ModelProvenance", name)
}

/** A failure reason as its snake case JSON value. */
internal enum class FailureReasonJson(val reason: FailureReason) {
    @JsonProperty("unavailable")
    UNAVAILABLE(FailureReason.UNAVAILABLE),

    @JsonProperty("invalid_response")
    INVALID_RESPONSE(FailureReason.INVALID_RESPONSE),
    ;

    companion object {
        fun of(reason: FailureReason): FailureReasonJson = when (reason) {
            FailureReason.UNAVAILABLE -> UNAVAILABLE
            FailureReason.INVALID_RESPONSE -> INVALID_RESPONSE
        }
    }
}

/** A proposition outcome as JSON. The `status` member names the result class. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "status")
@JsonSubTypes(
    JsonSubTypes.Type(PropositionAnsweredJson::class, name = "answered"),
    JsonSubTypes.Type(InconclusiveJson::class, name = "inconclusive"),
    JsonSubTypes.Type(FailureJson::class, name = "failure"),
)
internal sealed interface PropositionOutcomeJson {

    fun toProposition(): PropositionResult = when (this) {
        is PropositionAnsweredJson -> PropositionResult.Answered(answer, provenance.toProvenance(), pTrue)
        is InconclusiveJson -> PropositionResult.Inconclusive(provenance.toProvenance())
        is FailureJson -> PropositionResult.Failure(reason.reason)
    }

    companion object {
        fun of(result: PropositionResult): PropositionOutcomeJson = when (result) {
            is PropositionResult.Answered -> PropositionAnsweredJson(result.answer, result.pTrue, ProvenanceJson(result.provenance))
            is PropositionResult.Inconclusive -> InconclusiveJson(ProvenanceJson(result.provenance))
            is PropositionResult.Failure -> FailureJson(FailureReasonJson.of(result.reason))
        }
    }
}

/** A choice outcome as JSON. The `status` member names the result class. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "status")
@JsonSubTypes(
    JsonSubTypes.Type(SelectedJson::class, name = "selected"),
    JsonSubTypes.Type(NoMatchJson::class, name = "no_match"),
    JsonSubTypes.Type(InconclusiveJson::class, name = "inconclusive"),
    JsonSubTypes.Type(FailureJson::class, name = "failure"),
)
internal sealed interface ChoiceOutcomeJson {

    fun toClassification(): ClassificationResult = when (this) {
        is SelectedJson -> ClassificationResult.Selected(categoryId, provenance.toProvenance(), confidence)
        is NoMatchJson -> ClassificationResult.NoMatch(provenance.toProvenance())
        is InconclusiveJson -> ClassificationResult.Inconclusive(provenance.toProvenance())
        is FailureJson -> ClassificationResult.Failure(reason.reason)
    }

    companion object {
        fun of(result: ClassificationResult): ChoiceOutcomeJson = when (result) {
            is ClassificationResult.Selected -> SelectedJson(result.categoryId, result.confidence, ProvenanceJson(result.provenance))
            is ClassificationResult.NoMatch -> NoMatchJson(ProvenanceJson(result.provenance))
            is ClassificationResult.Inconclusive -> InconclusiveJson(ProvenanceJson(result.provenance))
            is ClassificationResult.Failure -> FailureJson(FailureReasonJson.of(result.reason))
        }
    }
}

@JsonPropertyOrder("answer", "pTrue", "provenance")
@JsonInclude(JsonInclude.Include.NON_NULL)
internal class PropositionAnsweredJson @JsonCreator constructor(
    @JsonProperty("answer", required = true) val answer: Boolean,
    @param:JsonProperty("pTrue") @get:JsonProperty("pTrue") val pTrue: Double?,
    @JsonProperty("provenance", required = true) val provenance: ProvenanceJson,
) : PropositionOutcomeJson {
    @JsonAnySetter
    private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("PropositionResult", name)
}

@JsonPropertyOrder("categoryId", "confidence", "provenance")
@JsonInclude(JsonInclude.Include.NON_NULL)
internal class SelectedJson @JsonCreator constructor(
    @JsonProperty("categoryId", required = true) val categoryId: String,
    @JsonProperty("confidence") val confidence: Double?,
    @JsonProperty("provenance", required = true) val provenance: ProvenanceJson,
) : ChoiceOutcomeJson {
    @JsonAnySetter
    private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("ClassificationResult", name)
}

internal class NoMatchJson @JsonCreator constructor(
    @JsonProperty("provenance", required = true) val provenance: ProvenanceJson,
) : ChoiceOutcomeJson {
    @JsonAnySetter
    private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("ClassificationResult", name)
}

internal class InconclusiveJson @JsonCreator constructor(
    @JsonProperty("provenance", required = true) val provenance: ProvenanceJson,
) : PropositionOutcomeJson, ChoiceOutcomeJson {
    @JsonAnySetter
    private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("an inconclusive outcome", name)
}

internal class FailureJson @JsonCreator constructor(
    @JsonProperty("reason", required = true) val reason: FailureReasonJson,
) : PropositionOutcomeJson, ChoiceOutcomeJson {
    @JsonAnySetter
    private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("a failure outcome", name)
}
