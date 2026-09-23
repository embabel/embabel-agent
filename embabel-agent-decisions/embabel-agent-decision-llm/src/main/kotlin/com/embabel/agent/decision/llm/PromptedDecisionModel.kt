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
package com.embabel.agent.decision.llm

import com.embabel.agent.decision.DecisionModel
import com.embabel.agent.spi.LlmService
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.util.EmbabelObjectMapperHolder
import org.jetbrains.annotations.ApiStatus
import java.nio.charset.StandardCharsets

/**
 * Produces verbalized probability evidence from any process-independent [LlmService].
 * The returned facade owns request preparation and all validation.
 */
@ApiStatus.Experimental
class PromptedDecisionModel private constructor() {
    companion object {
        /**
         * Creates a decision model registered under [LlmService.name].
         *
         * Provider identity is validated before any sender can be created.
         * [mapperHolder] is retained for factory compatibility. The protocol codec does not read
         * or mutate its mapper because application features and serializers cannot alter the wire contract.
         */
        @JvmStatic
        @JvmOverloads
        fun create(
            service: LlmService<*>,
            options: LlmOptions,
            mapperHolder: EmbabelObjectMapperHolder = EmbabelObjectMapperHolder.createDefault(),
        ): DecisionModel {
            val serviceName = requireNotNull(service.name) { "service.name must not be null" }
            require(serviceName.isNotBlank()) { "service.name must not be blank" }
            require(isSafeProvenanceText(serviceName)) { "service.name must be safe provenance text" }
            require(isSafeProvenanceText(service.provider)) { "service.provider must be safe provenance text" }
            return DecisionModel(
                PromptedProvider(
                    service = service,
                    options = options.copy(),
                    codec = PromptedWireCodec(mapperHolder),
                    requestedModel = serviceName,
                    serviceProvider = service.provider,
                ),
            ).named(serviceName, "prompted")
        }

        private fun isSafeProvenanceText(value: String): Boolean = value.isNotBlank() &&
            value.toByteArray(StandardCharsets.UTF_8).size <= 256 &&
            value.none { it == '\n' || it == '\r' }
    }
}
