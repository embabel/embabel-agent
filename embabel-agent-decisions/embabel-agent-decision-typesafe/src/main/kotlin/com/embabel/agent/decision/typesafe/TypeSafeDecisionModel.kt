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
package com.embabel.agent.decision.typesafe

import com.embabel.agent.decision.DecisionModel
import com.embabel.agent.decision.DecisionProvider
import com.embabel.common.util.EmbabelObjectMapperHolder
import org.jetbrains.annotations.ApiStatus
import java.net.URI
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.function.Supplier

/** Creates the experimental TypeSafe System One backed decision model. */
@ApiStatus.Experimental
object TypeSafeDecisionModel {
    /**
     * Creates a System One model with an isolated strict protocol codec.
     *
     * [mapperHolder] is retained for factory compatibility. Protocol parsing and generation do not
     * read or mutate its mapper because application features and serializers cannot alter the wire contract.
     */
    @JvmStatic
    @JvmOverloads
    fun create(
        apiKey: Supplier<String>,
        model: String,
        baseUri: URI = URI.create("https://api.typesafe.ai"),
        connectTimeout: Duration = Duration.ofSeconds(10),
        mapperHolder: EmbabelObjectMapperHolder = EmbabelObjectMapperHolder.createDefault(),
    ): DecisionModel {
        require(isSafeProvenanceText(model)) { "model must be safe provenance text" }
        require(!connectTimeout.isZero && !connectTimeout.isNegative) { "connect timeout must be positive" }
        require(supportsBaseUri(baseUri)) { "base URI must be an HTTPS origin" }
        val client = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
        val codec = JevWireCodec(mapperHolder, model)
        val transport = JevTransport(apiKey, model, baseUri, client, codec)
        return DecisionModel(DecisionProvider(transport::invoke)).named(model, "typesafe")
    }

    /**
     * Reports whether [uri] is a safe System One origin.
     *
     * HTTPS origins are accepted. Plain HTTP is limited to literal IPv4 or IPv6 loopback
     * origins so tests can use a local server without weakening production transport.
     */
    @JvmStatic
    fun supportsBaseUri(uri: URI): Boolean {
        if (!uri.isAbsolute || uri.userInfo != null || uri.query != null || uri.fragment != null) return false
        if (uri.path !in listOf("", "/")) return false
        return when (uri.scheme.lowercase()) {
            "https" -> uri.host != null
            "http" -> uri.host == "127.0.0.1" || uri.host in setOf("::1", "[::1]")
            else -> false
        }
    }

    private fun isSafeProvenanceText(value: String): Boolean = value.isNotBlank() &&
        value.toByteArray(StandardCharsets.UTF_8).size <= 256 &&
        value.none { it == '\n' || it == '\r' }
}
