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
package com.embabel.agent.autoconfigure.models.mistralai;

/**
 * Canned Mistral bodies for the hermetic Mistral tests, served by
 * {@link com.embabel.agent.test.http.StubChatServer}.
 */
final class MistralResponses {

    /** A valid, plain-string chat completion (no reasoning content) for {@code mistral-small-2603}. */
    static final String OK = """
            {"id":"cmpl-test","created":1700000000,"model":"mistral-small-2603","object":"chat.completion",\
            "usage":{"prompt_tokens":1,"total_tokens":2,"completion_tokens":1},\
            "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"OK"}}]}""";

    /** Mistral's 429 body, as returned when the per-minute request quota is hit. */
    static final String RATE_LIMITED = """
            {"object":"error","message":"Requests rate limit exceeded","type":"rate_limit_exceeded",\
            "param":null,"code":"3505"}""";

    /** Mistral's 400 body for a request that can never succeed however often it is replayed. */
    static final String BAD_REQUEST = """
            {"object":"error","message":"Invalid model: no-such-model","type":"invalid_model",\
            "param":null,"code":"1500"}""";

    private MistralResponses() {
    }
}
