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
package com.embabel.agent.systemone

import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.UnsupportedDecisionException
import com.embabel.common.ai.decision.systemone.SystemOneCodec
import com.embabel.common.ai.decision.systemone.SystemOneFormatException
import org.jetbrains.annotations.ApiStatus
import java.net.URI

/**
 * How an endpoint exposes System One over HTTP. The protocol's own layout is [STANDARD]:
 * decisions at `/v1/systemone`, the model name sent as given, the response body as the System One
 * response, and models listed at `/v1/models`. A hosting API that puts the model in the path or
 * wraps responses in its own envelope overrides the methods that differ.
 */
@ApiStatus.Experimental
interface SystemOneBinding {

    /**
     * Checks a model name when a service is built for it, so a name the endpoint can never serve
     * fails at once. The standard binding accepts every name and lets the endpoint decide.
     *
     * @param model the model name a service is being built for
     * @throws IllegalArgumentException if the endpoint can never serve the model
     */
    fun validateModel(model: String) {
        // The protocol itself puts no rules on model names.
    }

    /**
     * Returns where to send a model's question sets.
     *
     * @param endpoint the endpoint being called
     * @param model the model name the service was built for
     */
    fun decisionUri(endpoint: SystemOneEndpoint, model: String): URI = endpoint.resolve(SystemOneCodec.DECISION_PATH)

    /**
     * Returns the request body for a model's question set. The standard body is the System One
     * request with the model name as given.
     *
     * @param model the model name the service was built for
     * @param request the request being sent
     */
    fun decisionRequest(model: String, request: DecisionRequest): ByteArray = SystemOneCodec.encode(model, request)

    /**
     * Checks a request before the key is read or anything is sent. The standard binding accepts
     * every request.
     *
     * @param model the model name the service was built for
     * @param request the request about to be sent
     * @throws UnsupportedDecisionException if the endpoint can't take the request
     */
    fun check(model: String, request: DecisionRequest) {
        // The protocol itself sets no limits.
    }

    /**
     * Returns the System One response inside a successful response body.
     *
     * @param body the response body
     * @throws SystemOneFormatException if the body can't be read
     */
    fun decisionResponse(body: ByteArray): ByteArray = body

    /**
     * Returns where the endpoint lists its models.
     *
     * @param endpoint the endpoint being called
     */
    fun modelsUri(endpoint: SystemOneEndpoint): URI = endpoint.resolve(SystemOneCodec.MODELS_PATH)

    /**
     * Reads the model names from a successful listing body.
     *
     * @param body the listing body
     * @throws SystemOneFormatException if the body can't be read
     */
    fun modelNames(body: ByteArray): List<String> = SystemOneCodec.modelNames(body)

    companion object {

        /** The protocol's own layout. */
        @JvmField
        val STANDARD: SystemOneBinding = object : SystemOneBinding {
            override fun toString(): String = "SystemOneBinding.STANDARD"
        }
    }
}
