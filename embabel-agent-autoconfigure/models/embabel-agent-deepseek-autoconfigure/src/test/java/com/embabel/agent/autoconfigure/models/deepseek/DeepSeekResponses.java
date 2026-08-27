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
package com.embabel.agent.autoconfigure.models.deepseek;

/**
 * Canned DeepSeek bodies for the hermetic DeepSeek tests, served by
 * {@link com.embabel.agent.test.http.StubChatServer}. DeepSeek is OpenAI-compatible, so these
 * follow the chat-completion shape rather than Mistral's.
 */
final class DeepSeekResponses {

    /** A valid, plain-string chat completion for {@code deepseek-v4-pro}. */
    static final String OK = """
            {"id":"cmpl-test","object":"chat.completion","created":1700000000,"model":"deepseek-v4-pro",\
            "usage":{"prompt_tokens":1,"total_tokens":2,"completion_tokens":1},\
            "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"OK"}}]}""";

    /** DeepSeek's 429 body, as returned when the per-minute request quota is hit. */
    static final String RATE_LIMITED = """
            {"error":{"message":"Rate limit reached for requests","type":"rate_limit_error",\
            "param":null,"code":"rate_limit_exceeded"}}""";

    /** DeepSeek's 400 body for a request that can never succeed however often it is replayed. */
    static final String BAD_REQUEST = """
            {"error":{"message":"Model Not Exist","type":"invalid_request_error",\
            "param":null,"code":"invalid_request_error"}}""";

    private DeepSeekResponses() {
    }
}
