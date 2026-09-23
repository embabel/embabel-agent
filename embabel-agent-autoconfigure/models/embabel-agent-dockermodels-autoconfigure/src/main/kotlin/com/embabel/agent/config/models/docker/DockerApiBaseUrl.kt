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
 * The base URL the openai-java SDK needs, which is NOT the one [DockerConnectionProperties] holds.
 *
 * The SDK appends the bare path — `/embeddings`, `/chat/completions` — so its base has to end at
 * the API version, the way its own default `https://api.openai.com/v1` does.
 * `embabel.agent.models.docker.base-url` does not: its default is `http://localhost:12434/engines`,
 * and the model listing appends `/v1/models` to it. Two conventions in one property, and handing it
 * straight to the SDK meant discovery listed a runner's models and then EVERY call to one 404'd on
 * `/engines/embeddings` — chat as well as embedding. Verified against Docker Model Runner:
 * `/engines/embeddings` 404, `/engines/v1/embeddings` 200.
 *
 * Tolerant of a base that already ends at the version, because that is what LM Studio's property
 * holds and somebody setting this one by hand will reasonably copy it.
 */
internal fun dockerApiBaseUrl(baseUrl: String): String {
    val trimmed = baseUrl.trimEnd('/')
    return if (trimmed.endsWith("/v1")) trimmed else "$trimmed/v1"
}
