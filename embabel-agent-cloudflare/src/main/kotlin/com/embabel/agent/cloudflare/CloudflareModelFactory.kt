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
package com.embabel.agent.cloudflare

import com.embabel.agent.systemone.SystemOneEndpoint
import com.embabel.agent.systemone.SystemOneModelFactory
import com.embabel.common.byok.requireUsableApiKey
import io.micrometer.observation.ObservationRegistry
import org.jetbrains.annotations.ApiStatus
import org.springframework.web.client.RestClient
import java.net.URI
import java.util.function.Supplier

/**
 * Builds decision services for the decision models on Cloudflare Workers AI: the CLEF models
 * Cloudflare hosts, and Jev, which it runs as a third-party model. Workers AI serves System One
 * behind its own REST API, so this is a [SystemOneModelFactory] with Cloudflare's address for the
 * account, its envelope and its limits.
 *
 * Cloudflare always needs an API key, which is a Cloudflare API token. A missing key fails each call
 * as unavailable before anything is sent, and [buildValidated] rejects it. Building a service never
 * reads the key.
 *
 * @param accountId the Cloudflare account that runs the models, 32 hexadecimal characters
 * @param apiKey supplies the API key (a Cloudflare API token) with Workers AI access for each call
 * @param restClientBuilder the application's HTTP client builder, or null for the factory's own
 * @param observationRegistry records decision and request observations
 * @param defaultModel the model [build] and [buildValidated] return
 * @param maxResponseBytes the largest response body that is read
 */
@ApiStatus.Experimental
open class CloudflareModelFactory @JvmOverloads constructor(
    accountId: String,
    apiKey: Supplier<String?>,
    restClientBuilder: RestClient.Builder? = null,
    observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
    defaultModel: String = CLEF,
    maxResponseBytes: Int = SystemOneEndpoint.DEFAULT_MAX_RESPONSE_BYTES,
) : SystemOneModelFactory(
    endpoint(accountId, maxResponseBytes),
    defaultModel,
    requiringKey(apiKey),
    restClientBuilder,
    observationRegistry,
) {

    companion object {

        /** The provider name on every service and result this factory produces. */
        const val PROVIDER = "Cloudflare"

        /** The full CLEF model. */
        const val CLEF = "@cf/cloudflare/clef"

        /** The smaller, faster CLEF model. */
        const val CLEF_FLASH = "@cf/cloudflare/clef-flash"

        /** The mixture-of-experts CLEF model, which also reads audio. */
        const val CLEF_OMNI = "@cf/cloudflare/clef-omni"

        /** TypeSafe's Jev, which Cloudflare runs as a third-party model. */
        const val JEV = "typesafe/jev"

        /** The response header Cloudflare puts its request id in. */
        const val REQUEST_ID_HEADER = "cf-ray"

        private val API = URI.create("https://api.cloudflare.com/client/v4")
        private val ACCOUNT_ID = Regex("[0-9a-fA-F]{32}")

        /**
         * Builds the endpoint for an account. The account id goes into the address, so it must be
         * 32 hexadecimal characters, and the error leaves it out.
         *
         * @param accountId the Cloudflare account that runs the models
         * @param maxResponseBytes the largest response body that is read
         * @return the account's endpoint
         * @throws IllegalArgumentException if the account id is malformed
         */
        private fun endpoint(accountId: String, maxResponseBytes: Int): SystemOneEndpoint {
            require(ACCOUNT_ID.matches(accountId)) { "The Cloudflare account id must be 32 hexadecimal characters" }
            return SystemOneEndpoint(
                provider = PROVIDER,
                baseUri = URI.create("$API/accounts/$accountId"),
                maxResponseBytes = maxResponseBytes,
                requestIdHeader = REQUEST_ID_HEADER,
                binding = CloudflareBinding,
            )
        }

        /**
         * Wraps the key supplier so a missing or blank key fails the call. Cloudflare always needs a
         * key, unlike endpoints where a blank key just sends no Authorization header.
         *
         * @param apiKey supplies the API key for each call
         * @return a supplier that throws `InvalidApiKeyException` for a missing or blank key
         */
        private fun requiringKey(apiKey: Supplier<String?>): Supplier<String?> = Supplier {
            apiKey.get().also(::requireUsableApiKey)
        }
    }
}
