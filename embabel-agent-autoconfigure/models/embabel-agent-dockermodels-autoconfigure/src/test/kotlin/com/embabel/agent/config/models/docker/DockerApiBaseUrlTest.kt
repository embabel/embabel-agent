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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * One property, two conventions, and they have to be reconciled somewhere.
 *
 * `embabel.agent.models.docker.base-url` holds a base the model LISTING appends `/v1/models` to,
 * so its default stops at `/engines`. The openai-java SDK appends the bare path — `/embeddings`,
 * `/chat/completions` — so its base has to end at the API version, as its own default
 * `https://api.openai.com/v1` does.
 *
 * Handing the property straight to the SDK meant discovery listed a runner's models and then every
 * call to one 404'd. Verified against Docker Model Runner while testing an appliance's
 * `embabel embeddings use local`: `/engines/embeddings` 404, `/engines/v1/embeddings` 200. It is
 * not an embedding-only fault — a discovered chat model fails the same way, which is why this is
 * about the client and not about either caller.
 */
class DockerApiBaseUrlTest {

    @Test
    @DisplayName("the shipped default reaches the runner's API, not one path above it")
    fun `the default gains the version segment`() {
        assertEquals(
            "http://localhost:12434/engines/v1",
            dockerApiBaseUrl("http://localhost:12434/engines"),
            "the SDK appends `/embeddings`, so `/engines` alone 404s on a real runner",
        )
    }

    @Test
    @DisplayName("a base already ending at the version is left alone")
    fun `an explicit version is not doubled`() {
        // What LM Studio's own property holds, and so what somebody setting this one by hand will
        // reasonably copy. Doubling it would 404 just as surely, one segment further down.
        assertEquals(
            "http://model-runner.docker.internal/engines/v1",
            dockerApiBaseUrl("http://model-runner.docker.internal/engines/v1"),
        )
    }

    @Test
    @DisplayName("a trailing slash does not become an empty path segment")
    fun `trailing slashes are trimmed`() {
        assertEquals(
            "http://localhost:12434/engines/v1",
            dockerApiBaseUrl("http://localhost:12434/engines/"),
        )
        assertEquals(
            "http://localhost:12434/engines/v1",
            dockerApiBaseUrl("http://localhost:12434/engines/v1/"),
        )
    }
}
