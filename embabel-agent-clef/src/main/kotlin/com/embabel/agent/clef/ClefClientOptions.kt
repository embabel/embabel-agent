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

import com.embabel.common.ai.decision.systemone.SystemOneClientOptions
import org.jetbrains.annotations.ApiStatus
import java.net.URI
import java.time.Duration

/** Explicit loopback endpoint for an independently started experimental native host. */
@ApiStatus.Experimental
class ClefClientOptions @JvmOverloads constructor(
    val baseUrl: URI,
    val timeout: Duration = Duration.ofSeconds(45),
    val maxRequestBytes: Int = 16384,
    val maxResponseBytes: Int = 65536,
) {
    init {
        require(baseUrl.scheme == "http" && baseUrl.host in setOf("localhost", "127.0.0.1", "::1", "[::1]") &&
            baseUrl.rawUserInfo == null && baseUrl.rawQuery == null && baseUrl.rawFragment == null) {
            "CLEF requires an explicit loopback HTTP base URL without credentials, query or fragment"
        }
        require(maxRequestBytes in 1..16384 && maxResponseBytes in 1..65536) { "CLEF byte limits exceed the native bounds" }
        SystemOneClientOptions(baseUrl, timeout, maxRequestBytes, maxResponseBytes)
    }
    internal fun sharedOptions(): SystemOneClientOptions = SystemOneClientOptions(baseUrl, timeout, maxRequestBytes, maxResponseBytes)
    override fun toString(): String = "ClefClientOptions(timeout=$timeout, maxRequestBytes=$maxRequestBytes, maxResponseBytes=$maxResponseBytes)"
}
