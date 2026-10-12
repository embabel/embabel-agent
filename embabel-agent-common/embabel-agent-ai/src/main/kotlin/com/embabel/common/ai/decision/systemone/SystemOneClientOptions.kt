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
package com.embabel.common.ai.decision.systemone

import org.jetbrains.annotations.ApiStatus
import java.net.URI
import java.time.Duration

/** Limits retained request/response bytes and the complete HTTP exchange, including the streamed body. */
@ApiStatus.Experimental
class SystemOneClientOptions @JvmOverloads constructor(
    val baseUrl: URI,
    val timeout: Duration = Duration.ofSeconds(45),
    val maxRequestBytes: Int = 16384,
    val maxResponseBytes: Int = 65536,
) {
    init {
        require(baseUrl.isAbsolute && baseUrl.scheme.lowercase() in setOf("http", "https") &&
            !baseUrl.host.isNullOrBlank() && baseUrl.rawUserInfo == null && baseUrl.rawQuery == null && baseUrl.rawFragment == null) {
            "SystemOne base URL must be absolute HTTP(S), without credentials, query or fragment"
        }
        require(!timeout.isZero && !timeout.isNegative && timeout <= Duration.ofSeconds(300)) {
            "SystemOne timeout must be positive and no more than 300 seconds"
        }
        require(maxRequestBytes in 1..1048576 && maxResponseBytes in 1..1048576) {
            "SystemOne byte limits must be between 1 and 1048576"
        }
    }

    internal fun endpoint(route: String): URI = URI.create(baseUrl.toASCIIString().trimEnd('/') + "/v1/$route")

    override fun toString(): String = "SystemOneClientOptions(timeout=$timeout, maxRequestBytes=$maxRequestBytes, maxResponseBytes=$maxResponseBytes)"
}
