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

import com.embabel.agent.observability.SpanAttributes;

import com.embabel.agent.api.event.ToolLoopStartEvent;
import com.embabel.agent.api.event.observation.ToolLoopObservationContext;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.GlobalObservationConvention;
import io.micrometer.observation.Observation;

/**
 * Global convention for the {@code embabel.tool_loop} span. Reads from the
 * {@link ToolLoopStartEvent} wrapped by the thin {@link ToolLoopObservationContext}.
 */
public class EmbabelToolLoopObservationConvention
        implements GlobalObservationConvention<ToolLoopObservationContext> {

    private final int maxAttributeLength;
    private final boolean captureMessageContent;

    public EmbabelToolLoopObservationConvention(int maxAttributeLength) {
        this(maxAttributeLength, true);
    }

    /**
     * @param maxAttributeLength    truncation bound for message bodies
     * @param captureMessageContent when {@code false}, omit the {@code input.value} /
     *                              {@code output.value} bodies (may contain PII); metadata is
     *                              still recorded
     */
    public EmbabelToolLoopObservationConvention(int maxAttributeLength, boolean captureMessageContent) {
        this.maxAttributeLength = maxAttributeLength;
        this.captureMessageContent = captureMessageContent;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ToolLoopObservationContext;
    }

    @Override
    public String getName() {
        return SpanAttributes.EMBABEL_TOOL_LOOP;
    }

    @Override
    public String getContextualName(ToolLoopObservationContext context) {
        return "tool-loop";
    }

    @Override
    public KeyValues getLowCardinalityKeyValues(ToolLoopObservationContext context) {
        ToolLoopStartEvent event = context.getStartEvent();
        KeyValues kv = KeyValues.of(
                SpanAttributes.GEN_AI_OPERATION_NAME, "tool_loop",
                SpanAttributes.EMBABEL_EVENT_TYPE, "tool_loop",
                SpanAttributes.EMBABEL_AGENT_NAME, event.getAgentProcess().getAgent().getName(),
                SpanAttributes.EMBABEL_TOOL_LOOP_MAX_ITERATIONS, String.valueOf(event.getMaxIterations()));
        // Only the bounded short_name is a LOW-cardinality tag; the full (possibly fully-qualified)
        // action name is unbounded and goes to HIGH-cardinality below.
        if (event.getAction() != null) {
            kv = kv.and(SpanAttributes.EMBABEL_ACTION_SHORT_NAME,
                    ObservationUtils.shortName(event.getAction().getName()));
        }
        return kv;
    }

    @Override
    public KeyValues getHighCardinalityKeyValues(ToolLoopObservationContext context) {
        ToolLoopStartEvent event = context.getStartEvent();
        KeyValues kv = KeyValues.of(
                SpanAttributes.EMBABEL_RUN_ID, event.getAgentProcess().getId(),
                SpanAttributes.EMBABEL_INTERACTION_ID, event.getInteractionId(),
                SpanAttributes.EMBABEL_TOOL_LOOP_TOOL_NAMES, String.join(",", event.getToolNames()),
                SpanAttributes.EMBABEL_TOOL_LOOP_OUTPUT_CLASS, event.getOutputClass().getName());
        if (event.getAction() != null) {
            kv = kv.and(SpanAttributes.EMBABEL_ACTION_NAME, event.getAction().getName());
        }
        if (captureMessageContent) {
            String input = ObservationUtils.formatMessages(context.getInputMessages());
            if (!input.isEmpty()) {
                kv = kv.and(SpanAttributes.INPUT_VALUE, ObservationUtils.truncate(input, maxAttributeLength));
            }
            Object output = context.getOutput();
            if (output != null) {
                kv = kv.and(SpanAttributes.OUTPUT_VALUE, ObservationUtils.truncate(output.toString(), maxAttributeLength));
            }
        }
        return kv;
    }
}
