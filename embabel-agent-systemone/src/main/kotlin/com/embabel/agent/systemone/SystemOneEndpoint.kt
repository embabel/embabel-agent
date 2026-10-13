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

import org.jetbrains.annotations.ApiStatus
import java.net.URI
import java.time.Duration

/**
 * Where a System One endpoint lives and how to talk to it.
 *
 * The address must be absolute http or https and must not carry user info, a query or a
 * fragment, because those can hold credentials and would end up in logs. A base path is kept, so
 * `http://proxy.example/systemone` sends decisions to `http://proxy.example/systemone/v1/systemone`.
 * The timeouts and the response limit apply when the factory builds its own HTTP client. An
 * application client keeps its own timeouts, and the response limit still applies.
 *
 * @property provider the provider name reported in provenance, logs and observations
 * @property baseUri the endpoint address, without the protocol paths
 * @property connectTimeout how long to wait for a connection
 * @property readTimeout how long to wait for data once connected
 * @property maxResponseBytes the largest response body that will be read
 * @property requestIdHeader the response header that carries the provider's request id, if it sends one
 */
@ApiStatus.Experimental
class SystemOneEndpoint @JvmOverloads constructor(
    val provider: String,
    val baseUri: URI,
    val connectTimeout: Duration = DEFAULT_TIMEOUT,
    val readTimeout: Duration = DEFAULT_TIMEOUT,
    val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
    val requestIdHeader: String? = null,
) {

    init {
        require(provider.isNotBlank()) { "The provider name must not be blank" }
        require(baseUri.scheme in SCHEMES && !baseUri.host.isNullOrBlank()) {
            "The System One endpoint must be an absolute http or https address"
        }
        require(baseUri.rawUserInfo == null && baseUri.rawQuery == null && baseUri.rawFragment == null) {
            "The System One endpoint must not carry user info, a query or a fragment"
        }
        requireTimeout("connect", connectTimeout)
        requireTimeout("read", readTimeout)
        require(maxResponseBytes in 1 until Int.MAX_VALUE) { "The response limit must be positive" }
        require(requestIdHeader == null || requestIdHeader.isNotBlank()) { "The request id header must not be blank" }
    }

    /**
     * Returns the address of a protocol path under the base address.
     *
     * @param path a protocol path such as `/v1/models`
     * @return the full address
     */
    fun resolve(path: String): URI = URI.create(baseUri.toString().trimEnd('/') + path)

    override fun toString(): String =
        "SystemOneEndpoint(provider=$provider, baseUri=[CONFIGURED], connectTimeout=$connectTimeout, " +
            "readTimeout=$readTimeout, maxResponseBytes=$maxResponseBytes, requestIdHeader=$requestIdHeader)"

    companion object {

        /** The connect and read timeout used when none is given. */
        @JvmField
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(10)

        /** The response limit used when none is given, 1 MiB. */
        const val DEFAULT_MAX_RESPONSE_BYTES = 1024 * 1024

        private val SCHEMES = setOf("http", "https")

        private fun requireTimeout(name: String, timeout: Duration) {
            require(timeout >= Duration.ofMillis(1) && timeout.toMillis() <= Int.MAX_VALUE) {
                "The $name timeout must be between 1 ms and ${Int.MAX_VALUE} ms"
            }
        }
    }
}
