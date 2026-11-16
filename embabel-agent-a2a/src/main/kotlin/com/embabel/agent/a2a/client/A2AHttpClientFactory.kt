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
package com.embabel.agent.a2a.client

import io.a2a.client.http.A2AHttpClient

/**
 * Abstraction over the HTTP client used for outbound A2A calls.
 * Allows switching between Spring RestTemplate (OTel-instrumented) and
 * other implementations (JDK HttpClient, OkHttp, etc.) without coupling
 * callers to a specific transport.
 */
fun interface A2AHttpClientFactory {
    fun create(): A2AHttpClient
}
