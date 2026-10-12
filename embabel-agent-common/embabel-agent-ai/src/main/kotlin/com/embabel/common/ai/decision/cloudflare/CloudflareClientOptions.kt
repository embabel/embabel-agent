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
package com.embabel.common.ai.decision.cloudflare

import com.embabel.common.ai.decision.systemone.SystemOneClientOptions
import org.jetbrains.annotations.ApiStatus
import java.net.URI
import java.time.Duration

@ApiStatus.Experimental
class CloudflareClientOptions @JvmOverloads constructor(
    val accountId: String,
    val timeout: Duration = Duration.ofSeconds(20),
    val maxRequestBytes: Int = 262144,
    val maxResponseBytes: Int = 1048576,
) {
    init {
        require(accountId.matches(Regex("[A-Fa-f0-9]{32}"))) { "Cloudflare account ID must be 32 hexadecimal characters" }
        require(!timeout.isZero && !timeout.isNegative && timeout <= Duration.ofSeconds(300)) {
            "Cloudflare timeout must be positive and no more than 300 seconds"
        }
        require(maxRequestBytes in 1..1048576 && maxResponseBytes in 1..1048576) {
            "Cloudflare byte limits must be between 1 and 1048576"
        }
    }
    internal fun target(model: String): URI {
        require(model in CloudflareModelFactory.SUPPORTED_MODELS) { "Unsupported Cloudflare model" }
        return URI.create("https://api.cloudflare.com/client/v4/accounts/$accountId/ai/run/$model")
    }
    internal fun transportOptions() = SystemOneClientOptions(URI.create("https://api.cloudflare.com"),
        timeout, maxRequestBytes, maxResponseBytes)
    override fun toString(): String =
        "CloudflareClientOptions(timeout=$timeout, maxRequestBytes=$maxRequestBytes, maxResponseBytes=$maxResponseBytes)"
}
