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
package com.embabel.agent.config.models.docker

/**
 * Converts `embabel.agent.models.docker.base-url` into the base URL the openai-java SDK expects.
 *
 * The SDK appends paths such as `/embeddings` directly, so its base must end in `/v1`.
 * Model listing uses that same base and appends `/models`.
 *
 * - `http://localhost:12434/engines`     -> `http://localhost:12434/engines/v1`
 * - `http://localhost:12434/engines/`    -> `http://localhost:12434/engines/v1`
 * - `http://localhost:12434/engines/v1`  -> `http://localhost:12434/engines/v1` (unchanged)
 *
 * Internal rather than private: [DockerLocalModelsConfig] calls it from another file.
 */
internal fun dockerApiBaseUrl(baseUrl: String): String {
    val trimmed = baseUrl.trimEnd('/')
    return if (trimmed.endsWith("/v1")) trimmed else "$trimmed/v1"
}

/** Listing URL. `/v1` is added once, even when [baseUrl] already ends in it. */
internal fun dockerModelsUrl(baseUrl: String): String =
    "${dockerApiBaseUrl(baseUrl)}/models"
