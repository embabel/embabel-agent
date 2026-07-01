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
package com.embabel.agent.observability.tracing;

import com.embabel.agent.observability.SpanAttributes;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.observation.ChatModelObservationContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Observation filter that enriches ChatModel observations with the vendor-neutral OpenTelemetry
 * GenAI semantic conventions.
 *
 * <p>This filter intercepts Spring AI ChatModel observations and extracts model info, token usage,
 * and (opt-in) prompt/response message content, adding them as key values for tracing. It emits
 * only standard {@code gen_ai.*} keys; backend-specific mappings (e.g. LangSmith
 * {@code langsmith.span.kind}) are the responsibility of dedicated exporters, not this core filter.
 *
 * <p>OpenTelemetry GenAI semantic convention attributes added:
 * <ul>
 *   <li>{@code gen_ai.operation.name} - Always "chat" for chat model operations</li>
 *   <li>{@code gen_ai.provider.name} - The provider name from the observation metadata (e.g. "openai")</li>
 *   <li>{@code gen_ai.request.model} - The model name from the request</li>
 *   <li>{@code gen_ai.response.model} - The model name from the response</li>
 *   <li>{@code gen_ai.request.temperature} - Temperature setting if available</li>
 *   <li>{@code gen_ai.request.max_tokens} - Max tokens setting if available</li>
 *   <li>{@code gen_ai.request.top_p} - Top-p (nucleus sampling) setting if available</li>
 *   <li>{@code gen_ai.usage.input_tokens} - Number of tokens in the prompt</li>
 *   <li>{@code gen_ai.usage.output_tokens} - Number of tokens in the response</li>
 *   <li>{@code gen_ai.input.messages} - JSON array of input messages (opt-in content)</li>
 *   <li>{@code gen_ai.output.messages} - JSON array of output messages (opt-in content)</li>
 * </ul>
 *
 * <p>For backend compatibility it also emits the OpenInference {@code input.value} /
 * {@code output.value} bridge while content capture is enabled. Content capture is governed by
 * {@code captureMessageContent}; the GenAI convention recommends it be opt-in (potential PII).
 *
 * @see <a href="https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-spans/">OpenTelemetry GenAI Spans</a>
 */
public class ChatModelObservationFilter implements ObservationFilter {

    private static final Logger log = LoggerFactory.getLogger(ChatModelObservationFilter.class);

    private final int maxAttributeLength;
    private final boolean captureMessageContent;

    /**
     * Creates a new filter with default max attribute length of 4000 and content capture enabled.
     */
    public ChatModelObservationFilter() {
        this(4000);
    }

    /**
     * Creates a new filter with the specified max attribute length and content capture enabled.
     *
     * @param maxAttributeLength maximum length for message content values before truncation
     */
    public ChatModelObservationFilter(int maxAttributeLength) {
        this(maxAttributeLength, true);
    }

    /**
     * Creates a new filter with the specified max attribute length and content capture flag.
     *
     * @param maxAttributeLength    maximum length for message content values before truncation
     * @param captureMessageContent whether to capture prompt/response message bodies
     */
    public ChatModelObservationFilter(int maxAttributeLength, boolean captureMessageContent) {
        this.maxAttributeLength = maxAttributeLength;
        this.captureMessageContent = captureMessageContent;
    }

    /**
     * Enriches a {@link ChatModelObservationContext} with GenAI semantic convention key-values.
     *
     * <p>Adds low-cardinality keys for model names and operation type, and high-cardinality keys for
     * hyperparameters, token usage, and (opt-in) structured message content. Non-ChatModel contexts
     * are returned unchanged.
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
            context.addLowCardinalityKeyValue(KeyValue.of(SpanAttributes.GEN_AI_OPERATION_NAME, "chat"));

            // Provider name from the observation metadata (e.g. "openai", "anthropic", "ollama")
            var operationMetadata = chatContext.getOperationMetadata();
            if (operationMetadata != null) {
                String provider = operationMetadata.provider();
                if (provider != null && !provider.isEmpty()) {
                    context.addLowCardinalityKeyValue(KeyValue.of(SpanAttributes.GEN_AI_PROVIDER_NAME, provider));
                }
            }

            // Extract model info from request
            var request = chatContext.getRequest();
            if (request != null && request.getOptions() != null) {
                String model = request.getOptions().getModel();
                if (model != null && !model.isEmpty()) {
                    context.addLowCardinalityKeyValue(KeyValue.of(SpanAttributes.GEN_AI_REQUEST_MODEL, model));
                }
                if (request.getOptions().getTemperature() != null) {
                    context.addHighCardinalityKeyValue(KeyValue.of(SpanAttributes.GEN_AI_REQUEST_TEMPERATURE,
                            String.valueOf(request.getOptions().getTemperature())));
                }
                if (request.getOptions().getMaxTokens() != null) {
                    context.addHighCardinalityKeyValue(KeyValue.of(SpanAttributes.GEN_AI_REQUEST_MAX_TOKENS,
                            String.valueOf(request.getOptions().getMaxTokens())));
                }
                if (request.getOptions().getTopP() != null) {
                    context.addHighCardinalityKeyValue(KeyValue.of(SpanAttributes.GEN_AI_REQUEST_TOP_P,
                            String.valueOf(request.getOptions().getTopP())));
                }
            }

            // Extract response model and token usage
            var response = chatContext.getResponse();
            if (response != null && response.getMetadata() != null) {
                String responseModel = response.getMetadata().getModel();
                if (responseModel != null && !responseModel.isEmpty()) {
                    context.addLowCardinalityKeyValue(KeyValue.of(SpanAttributes.GEN_AI_RESPONSE_MODEL, responseModel));
                }
                var usage = response.getMetadata().getUsage();
                if (usage != null) {
                    if (usage.getPromptTokens() != null) {
                        context.addHighCardinalityKeyValue(KeyValue.of(SpanAttributes.GEN_AI_USAGE_INPUT_TOKENS,
                                String.valueOf(usage.getPromptTokens())));
                    }
                    if (usage.getCompletionTokens() != null) {
                        context.addHighCardinalityKeyValue(KeyValue.of(SpanAttributes.GEN_AI_USAGE_OUTPUT_TOKENS,
                                String.valueOf(usage.getCompletionTokens())));
                    }
                }
            }

            // Message content (opt-in: may contain PII, per the GenAI semantic convention)
            if (captureMessageContent) {
                addMessageContent(context, chatContext);
            }

        } catch (Exception e) {
            log.debug("Failed to extract GenAI attributes from ChatModelObservationContext", e);
        }

        return context;
    }

    /**
     * Adds the structured GenAI message attributes ({@code gen_ai.input.messages} /
     * {@code gen_ai.output.messages}) plus the OpenInference {@code input.value} / {@code output.value}
     * bridge. Each message body is truncated per part before serialization so the emitted JSON stays
     * well-formed.
     */
    private void addMessageContent(Observation.Context context, ChatModelObservationContext chatContext) {
        String inputMessages = buildInputMessages(chatContext);
        if (inputMessages != null) {
            context.addHighCardinalityKeyValue(KeyValue.of(SpanAttributes.GEN_AI_INPUT_MESSAGES, inputMessages));
        }
        String outputMessages = buildOutputMessages(chatContext);
        if (outputMessages != null) {
            context.addHighCardinalityKeyValue(KeyValue.of(SpanAttributes.GEN_AI_OUTPUT_MESSAGES, outputMessages));
        }

        // OpenInference bridge, kept until a dedicated exporter derives it from the GenAI messages.
        String prompt = extractPrompt(chatContext);
        if (prompt != null && !prompt.isEmpty()) {
            context.addHighCardinalityKeyValue(KeyValue.of(SpanAttributes.INPUT_VALUE, truncate(prompt)));
        }
        String completion = extractCompletion(chatContext);
        if (completion != null && !completion.isEmpty()) {
            context.addHighCardinalityKeyValue(KeyValue.of(SpanAttributes.OUTPUT_VALUE, truncate(completion)));
        }
    }

    /**
     * Builds the {@code gen_ai.input.messages} value: a JSON array of
     * {@code {"role": ..., "parts": [{"type": "text", "content": ...}]}} objects, one per request
     * message. Returns {@code null} when there are no instructions.
     */
    private String buildInputMessages(ChatModelObservationContext chatContext) {
        var request = chatContext.getRequest();
        var instructions = request.getInstructions();
        if (instructions == null || instructions.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        for (var message : instructions) {
            // Spring AI MessageType.getValue() already yields lowercase GenAI roles
            // (system / user / assistant / tool).
            messages.add(textMessage(message.getMessageType().getValue(), message.getText(), null));
        }
        return toJson(messages);
    }

    /**
     * Builds the {@code gen_ai.output.messages} value: a JSON array of assistant messages, one per
     * generation, each carrying its {@code finish_reason} when available. Returns {@code null} when
     * the response has no usable output.
     */
    private String buildOutputMessages(ChatModelObservationContext chatContext) {
        var response = chatContext.getResponse();
        if (response == null || response.getResults() == null) {
            return null;
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        for (var generation : response.getResults()) {
            if (generation == null || generation.getOutput() == null) {
                continue;
            }
            String finishReason = generation.getMetadata() != null
                    ? generation.getMetadata().getFinishReason()
                    : null;
            messages.add(textMessage("assistant", generation.getOutput().getText(), finishReason));
        }
        return messages.isEmpty() ? null : toJson(messages);
    }

    /**
     * Builds a single GenAI message object with one text part. The content is truncated here so the
     * serialized JSON never exceeds the configured limit mid-structure.
     */
    private Map<String, Object> textMessage(String role, String content, String finishReason) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "text");
        part.put("content", truncate(content));

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("parts", List.of(part));
        if (finishReason != null && !finishReason.isEmpty()) {
            message.put("finish_reason", finishReason);
        }
        return message;
    }

    /**
     * Extracts the user prompt from the chat request instructions for the OpenInference bridge.
     * Formats each message as {@code [role]: text} (role spelled lowercase, matching the GenAI
     * convention), joined by newlines.
     *
     * @return the formatted prompt string, or {@code null} if no instructions are available
     */
    private String extractPrompt(ChatModelObservationContext chatContext) {
        var request = chatContext.getRequest();
        var instructions = request.getInstructions();
        if (instructions == null || instructions.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (var message : instructions) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            // Spring AI MessageType.getValue() yields the lowercase GenAI role, matching the
            // gen_ai.input.messages convention above so both attributes spell the role identically.
            sb.append("[").append(message.getMessageType().getValue()).append("]: ");
            sb.append(message.getText());
        }
        return sb.toString();
    }

    /**
     * Extracts the LLM completion text from the chat response for the OpenInference bridge.
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
     * Serializes a value to a compact JSON string with no external dependency. Supports the limited
     * shapes used here: maps, lists, strings, and JSON-safe scalars (numbers, booleans, null).
     *
     * @return the JSON string, or {@code null} if serialization fails
     */
    private String toJson(Object value) {
        try {
            StringBuilder sb = new StringBuilder();
            writeJson(sb, value);
            return sb.toString();
        } catch (RuntimeException e) {
            log.debug("Failed to serialize GenAI messages to JSON", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static void writeJson(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeJsonString(sb, s);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) map).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeJsonString(sb, entry.getKey());
                sb.append(':');
                writeJson(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeJson(sb, item);
            }
            sb.append(']');
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else {
            writeJsonString(sb, value.toString());
        }
    }

    /** Appends a JSON string literal, escaping per RFC 8259 (quotes, backslash, control chars). */
    private static void writeJsonString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
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
