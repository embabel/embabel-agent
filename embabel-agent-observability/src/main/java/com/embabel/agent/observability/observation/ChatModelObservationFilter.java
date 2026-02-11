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
package com.embabel.agent.observability.observation;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.observation.ChatModelObservationContext;

/**
 * Observation filter that enriches ChatModel observations with OpenTelemetry GenAI semantic conventions.
 *
 * <p>This filter intercepts Spring AI ChatModel observations and extracts model info,
 * token usage, prompts and completions, adding them as key values for tracing.
 *
 * <p>OpenTelemetry GenAI semantic convention attributes added:
 * <ul>
 *   <li>{@code gen_ai.operation.name} - Always "chat" for chat model operations</li>
 *   <li>{@code gen_ai.request.model} - The model name from the request</li>
 *   <li>{@code gen_ai.response.model} - The model name from the response</li>
 *   <li>{@code gen_ai.request.temperature} - Temperature setting if available</li>
 *   <li>{@code gen_ai.request.max_tokens} - Max tokens setting if available</li>
 *   <li>{@code gen_ai.usage.input_tokens} - Number of tokens in the prompt</li>
 *   <li>{@code gen_ai.usage.output_tokens} - Number of tokens in the response</li>
 *   <li>{@code gen_ai.prompt} - The user prompt sent to the LLM</li>
 *   <li>{@code gen_ai.completion} - The LLM response/completion</li>
 *   <li>{@code input.value} - Same as prompt</li>
 *   <li>{@code output.value} - Same as completion</li>
 * </ul>
 *
 * @see <a href="https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-spans/">OpenTelemetry GenAI Spans</a>
 */
public class ChatModelObservationFilter implements ObservationFilter {

    private static final Logger log = LoggerFactory.getLogger(ChatModelObservationFilter.class);

    private final int maxAttributeLength;

    /**
     * Creates a new filter with default max attribute length of 4000.
     */
    public ChatModelObservationFilter() {
        this(4000);
    }

    /**
     * Creates a new filter with specified max attribute length.
     *
     * @param maxAttributeLength maximum length for prompt/completion values before truncation
     */
    public ChatModelObservationFilter(int maxAttributeLength) {
        this.maxAttributeLength = maxAttributeLength;
    }

    /**
     * Enriches a {@link ChatModelObservationContext} with GenAI semantic convention key-values.
     *
     * <p>Adds low-cardinality keys for model names and operation type, and high-cardinality
     * keys for hyperparameters, token usage, prompt content and completion content.
     * Non-ChatModel contexts are returned unchanged.
     *
     * @param context the observation context to enrich
     * @return the enriched context (same instance)
     */
    @Override
    @NotNull
    public Observation.Context map(@NotNull Observation.Context context) {
        if (!(context instanceof ChatModelObservationContext chatContext)) {
            return context;
        }

        try {
            // OpenTelemetry GenAI semantic conventions
            context.addLowCardinalityKeyValue(KeyValue.of("gen_ai.operation.name", "chat"));

            // Extract model info from request
            var request = chatContext.getRequest();
            if (request != null && request.getOptions() != null) {
                String model = request.getOptions().getModel();
                if (model != null && !model.isEmpty()) {
                    context.addLowCardinalityKeyValue(KeyValue.of("gen_ai.request.model", model));
                }
                if (request.getOptions().getTemperature() != null) {
                    context.addHighCardinalityKeyValue(KeyValue.of("gen_ai.request.temperature",
                            String.valueOf(request.getOptions().getTemperature())));
                }
                if (request.getOptions().getMaxTokens() != null) {
                    context.addHighCardinalityKeyValue(KeyValue.of("gen_ai.request.max_tokens",
                            String.valueOf(request.getOptions().getMaxTokens())));
                }
            }

            // Extract response model and token usage
            var response = chatContext.getResponse();
            if (response != null && response.getMetadata() != null) {
                String responseModel = response.getMetadata().getModel();
                if (responseModel != null && !responseModel.isEmpty()) {
                    context.addLowCardinalityKeyValue(KeyValue.of("gen_ai.response.model", responseModel));
                }
                var usage = response.getMetadata().getUsage();
                if (usage != null) {
                    if (usage.getPromptTokens() != null) {
                        context.addHighCardinalityKeyValue(KeyValue.of("gen_ai.usage.input_tokens",
                                String.valueOf(usage.getPromptTokens())));
                    }
                    if (usage.getCompletionTokens() != null) {
                        context.addHighCardinalityKeyValue(KeyValue.of("gen_ai.usage.output_tokens",
                                String.valueOf(usage.getCompletionTokens())));
                    }
                }
            }

            // Extract prompt from request
            String prompt = extractPrompt(chatContext);
            if (prompt != null && !prompt.isEmpty()) {
                String truncated = truncate(prompt);
                context.addHighCardinalityKeyValue(KeyValue.of("gen_ai.prompt", truncated));
                context.addHighCardinalityKeyValue(KeyValue.of("input.value", truncated));
            }

            // Extract completion from response
            String completion = extractCompletion(chatContext);
            if (completion != null && !completion.isEmpty()) {
                String truncated = truncate(completion);
                context.addHighCardinalityKeyValue(KeyValue.of("gen_ai.completion", truncated));
                context.addHighCardinalityKeyValue(KeyValue.of("output.value", truncated));
            }

        } catch (Exception e) {
            log.debug("Failed to extract prompt/completion from ChatModelObservationContext", e);
        }

        return context;
    }

    /**
     * Extracts the user prompt from the chat request instructions.
     * Formats each message as {@code [MESSAGE_TYPE]: text}, joined by newlines.
     *
     * @return the formatted prompt string, or {@code null} if no instructions are available
     */
    private String extractPrompt(ChatModelObservationContext chatContext) {
        var request = chatContext.getRequest();
        if (request == null) {
            return null;
        }

        var instructions = request.getInstructions();
        if (instructions == null || instructions.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (var message : instructions) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append("[").append(message.getMessageType()).append("]: ");
            sb.append(message.getText());
        }
        return sb.toString();
    }

     /**
     * Extracts the LLM completion text from the chat response.
     *
     * @return the completion text, or {@code null} if the response or its output is unavailable
     */
    private String extractCompletion(ChatModelObservationContext chatContext) {
        var response = chatContext.getResponse();
        if (response == null) {
            return null;
        }

        var result = response.getResult();
        if (result == null || result.getOutput() == null) {
            return null;
        }

        return result.getOutput().getText();
    }

    /**
     * Truncates a value to {@link #maxAttributeLength}, appending "..." if truncated.
     *
     * @return the truncated string, or empty string if {@code null}
     */
    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > maxAttributeLength
                ? value.substring(0, maxAttributeLength) + "..."
                : value;
    }
}
