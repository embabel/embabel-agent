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
package com.embabel.agent.clef

import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.*
import com.embabel.common.ai.decision.systemone.SystemOneModelProfile
import com.embabel.common.ai.decision.systemone.SystemOneResponseMetadata
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

internal class ClefProtocol : SystemOneModelProfile {
    override fun validateRequest(request: DecisionRequest) {
        require(request.input.toByteArray(Charsets.UTF_8).size <= 4096) { "CLEF state exceeds its UTF-8 bound" }
        require(request.spec.questions.size in 1..16) { "CLEF question count exceeds the native head" }
        var options = 0
        for (question in request.spec.questions) {
            boundedText(question.instructions)
            options += when (question) {
                is PropositionQuestionSpec -> 2
                is ChoiceQuestionSpec -> {
                    require(question.options.size in 2..32) { "CLEF choice count exceeds the native head" }
                    question.options.forEach { boundedText(it.description.ifBlank { it.id }) }
                    question.options.size
                }
                is RatingQuestionSpec -> {
                    require(question.levels.size in 2..16) { "CLEF rating count exceeds the native head" }
                    question.levels.forEach { boundedText(it.description.ifBlank { it.id }) }
                    question.levels.size
                }
            }
        }
        require(options <= 96) { "CLEF aggregate options exceed the native head" }
    }
    override fun provenance(metadata: SystemOneResponseMetadata): ModelProvenance {
        require(metadata.requestedModel == ClefModelFactory.MODEL && metadata.actualModel == ClefModelFactory.MODEL &&
            metadata.inputTokens in 1..1023 && metadata.outputTokens == 0L) { "Invalid CLEF response identity or usage" }
        val values = metadata.headers.entries.filter { it.key.equals(HEADER, ignoreCase = true) }.flatMap { it.value }
        require(values.size == 1) { "CLEF requires exactly one provenance header" }
        val header = values.single()
        require(header.length <= 8192 && header.all { it.code in 0..127 }) { "Invalid CLEF provenance encoding or bound" }
        val root = mapper.readTree(header)
        require(root.isObject && root.propertyNames().toSet() == FIELDS) { "Invalid CLEF provenance fields" }
        require(root.get("experimental")?.isBoolean == true && root.get("experimental").booleanValue()) { "CLEF must be experimental" }
        val expected = mapOf("protocol" to "embabel-clef-systemone-v1", "source_repo" to "FluidInference/clef-text-0.6b-coreml",
            "source_revision" to SOURCE, "variant" to VARIANT, "license" to "Apache-2.0", "requested_compute" to "cpu",
            "confidence_semantics" to "selected_probability", "checker_source_revision" to RUNTIME,
            "checker_provenance" to "operator-supplied source/build; digest identifies bytes, not origin")
        require(expected.all { (key, value) -> text(root, key) == value }) { "Untrusted CLEF provenance identity" }
        val hashes = root.get("asset_sha256")
        require(hashes != null && hashes.isObject && hashes.propertyNames().toSet() == PINNED_HASHES.keys &&
            PINNED_HASHES.all { (key, value) -> text(hashes, key) == value }) { "Untrusted CLEF input hashes" }
        val checker = text(root, "checker_sha256")
        require(checker.matches(Regex("[0-9a-f]{64}")) && text(root, "checker_build_id").matches(Regex("[A-Za-z0-9_.-]{1,128}"))) {
            "Invalid CLEF checker provenance"
        }
        return ModelProvenance(ClefModelFactory.MODEL, ClefModelFactory.PROVIDER, "$SOURCE/$VARIANT/cpu/checker=$checker")
    }
    override fun failureReason(statusCode: Int): FailureReason =
        if (statusCode == 502) FailureReason.INVALID_RESPONSE else super.failureReason(statusCode)

    private fun boundedText(value: String) {
        require(value.isNotBlank() && value.toByteArray(Charsets.UTF_8).size <= 512) { "CLEF question text exceeds its native bound" }
    }
    private fun text(node: JsonNode, key: String): String {
        val value = node.get(key)
        require(value != null && value.isString) { "Invalid CLEF provenance string" }
        return value.stringValue()
    }
    companion object {
        private const val HEADER = "x-embabel-clef-provenance"
        private const val SOURCE = "3c61a60954478ef385aa42d67d5764642dcc9135"
        private const val RUNTIME = "0230049c4e81f8de53ae1086b20cd9106c511105"
        private const val VARIANT = "local-single-function-L1024"
        private val mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()
        private val FIELDS = setOf("source_repo", "source_revision", "variant", "license", "requested_compute", "asset_sha256",
            "checker_sha256", "checker_source_revision", "checker_build_id", "checker_provenance", "confidence_semantics", "protocol", "experimental")
        val PINNED_HASHES: Map<String, String> = java.util.Collections.unmodifiableMap(linkedMapOf(
            "Decoder.mlpackage/Data/com.apple.CoreML/model.mlmodel" to "49049d20da650ffa67b550ce0ac08f12aff305b4f9e6bbfe6dbc8e46408ab2e1",
            "Head.mlpackage/Data/com.apple.CoreML/model.mlmodel" to "71045bc150f3091142578e40bde13f7db5b3435d127af7f1c7dda6b637a31ec4",
            "Decoder.mlpackage/Data/com.apple.CoreML/weights/weight.bin" to "cbe7a6f22befa4edee09e7c806b2b0b9db8533d1ede737540fa82a0261435742",
            "Head.mlpackage/Data/com.apple.CoreML/weights/weight.bin" to "d028526027d6ce12313fbc80ad35f148ecb815f09f83695c5bb2b5872dbd4657",
            "config.json" to "af356b87b840722b77068df83612d6ad6efdd19804c6c955e4689a0e10c6bf58",
            "tokenizer.json" to "be75606093db2094d7cd20f3c2f385c212750648bd6ea4fb2bf507a6a4c55506",
            "embeddings.f16" to "616f9374ddbaae3a033a0174557d502719154882e21e5d975ab5b58e0244459d",
        ))
    }
}
