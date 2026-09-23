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
import java.time.Duration
import java.util.function.Supplier

/** Creates the experimental TypeSafe System One backed decision model. */
@ApiStatus.Experimental
object TypeSafeDecisionModel {
    @JvmStatic
    @JvmOverloads
    fun create(
        apiKey: Supplier<String>,
        model: String,
        baseUri: URI = URI.create("https://api.typesafe.ai"),
        connectTimeout: Duration = Duration.ofSeconds(10),
        mapperHolder: EmbabelObjectMapperHolder = EmbabelObjectMapperHolder.createDefault(),
    ): DecisionModel {
        require(model.isNotBlank()) { "model must not be blank" }
        require(!connectTimeout.isZero && !connectTimeout.isNegative) { "connect timeout must be positive" }
        require(validBaseUri(baseUri)) { "base URI must be an HTTPS origin" }
        val client = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
        val codec = JevWireCodec(mapperHolder, model)
        val transport = JevTransport(apiKey, model, baseUri, client, codec)
        return DecisionModel(DecisionProvider(transport::invoke))
    }

    private fun validBaseUri(uri: URI): Boolean {
        if (!uri.isAbsolute || uri.userInfo != null || uri.query != null || uri.fragment != null) return false
        if (uri.path !in listOf("", "/")) return false
        return when (uri.scheme.lowercase()) {
            "https" -> uri.host != null
            "http" -> uri.host == "127.0.0.1" || uri.host in setOf("::1", "[::1]")
            else -> false
        }
    }
}
