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
package com.embabel.agent.config.models.mistralai;

import org.springframework.ai.mistralai.api.MistralAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Flattens reasoning-model ("thinking") response content to plain text before {@code MistralAiChatModel}
 * reads it, working on Spring AI's own record types (no HTTP byte rewriting, no custom deserializer).
 *
 * <p>Reasoning models ({@code magistral-*}) return an assistant {@code content} that is a list of
 * thinking/text chunks, but {@code spring-ai-mistral-ai:1.1.7} assumes a String and throws
 * {@code IllegalStateException("The content is not a string!")} on every call. Fixed natively in Spring
 * AI 2.0.0 — this decorator is temporary scaffolding, removed on that upgrade.
 *
 * <p>Only the blocking path ({@link #chatCompletionEntity}) is decorated; streaming is unchanged.
 * The chunk-to-text flattening itself lives in {@link MistralReasoningContent}.
 */
public class ReasoningFlatteningMistralAiApi extends MistralAiApi {

    private static final String DEFAULT_BASE_URL = "https://api.mistral.ai";

    /** Applies {@code MistralAiApi.Builder}'s own defaults for base URL and error handler. */
    public ReasoningFlatteningMistralAiApi(
            String baseUrl,
            String apiKey,
            RestClient.Builder restClientBuilder,
            WebClient.Builder webClientBuilder) {
        super(
                (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl,
                apiKey,
                restClientBuilder,
                webClientBuilder,
                RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER);
    }

    @Override
    public ResponseEntity<ChatCompletion> chatCompletionEntity(ChatCompletionRequest chatRequest) {
        ResponseEntity<ChatCompletion> response = super.chatCompletionEntity(chatRequest);
        ChatCompletion body = response.getBody();
        if (body == null || body.choices() == null) {
            return response;
        }

        List<ChatCompletion.Choice> rewrittenChoices = new ArrayList<>(body.choices().size());
        for (ChatCompletion.Choice choice : body.choices()) {
            ChatCompletionMessage message = choice.message();
            if (message != null && message.rawContent() instanceof List<?> chunks) {
                ChatCompletionMessage flatMessage = new ChatCompletionMessage(
                        MistralReasoningContent.flatten(chunks),
                        message.role(),
                        message.name(),
                        message.toolCalls(),
                        message.toolCallId());
                rewrittenChoices.add(new ChatCompletion.Choice(
                        choice.index(), flatMessage, choice.finishReason(), choice.logprobs()));
            } else {
                rewrittenChoices.add(choice);
            }
        }

        ChatCompletion rewritten = new ChatCompletion(
                body.id(), body.object(), body.created(), body.model(), rewrittenChoices, body.usage());
        return new ResponseEntity<>(rewritten, response.getHeaders(), response.getStatusCode());
    }
}
