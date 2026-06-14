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

import com.embabel.agent.api.event.observation.ActionObservationContext;
import com.embabel.agent.core.AgentProcess;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.GlobalObservationConvention;
import io.micrometer.observation.Observation;

/**
 * Global convention for the {@code embabel.action} span (one action execution).
 * Reads from the thin {@link ActionObservationContext}; no extraction lives in the core.
 */
public class EmbabelActionObservationConvention
        implements GlobalObservationConvention<ActionObservationContext> {

    private final int maxAttributeLength;

    public EmbabelActionObservationConvention(int maxAttributeLength) {
        this.maxAttributeLength = maxAttributeLength;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ActionObservationContext;
    }

    @Override
    public String getName() {
        return SpanAttributes.EMBABEL_ACTION;
    }

    @Override
    public String getContextualName(ActionObservationContext context) {
        return "action " + ObservationUtils.shortName(context.getAction().getName());
    }

    @Override
    public KeyValues getLowCardinalityKeyValues(ActionObservationContext context) {
        String name = context.getAction().getName();
        // Only the bounded short_name is a LOW-cardinality tag. The full action name can be
        // fully-qualified (com.pkg.Agent.method, or com.pkg.In=>com.pkg.Out-N) and is therefore
        // unbounded across extension packages — it belongs in HIGH-cardinality, not as a metric
        // dimension.
        KeyValues kv = KeyValues.of(
                SpanAttributes.GEN_AI_OPERATION_NAME, "action",
                SpanAttributes.EMBABEL_EVENT_TYPE, "action",
                SpanAttributes.EMBABEL_ACTION_SHORT_NAME, ObservationUtils.shortName(name),
                SpanAttributes.EMBABEL_AGENT_NAME, context.getProcess().getAgent().getName());
        // Status is known only once the action has run (set on the context before stop). A failed
        // status is recorded as a tag only — the span is errored solely when the work throws.
        if (context.getStatusCode() != null) {
            kv = kv.and(SpanAttributes.EMBABEL_ACTION_STATUS, context.getStatusCode().name());
        }
        return kv;
    }

    @Override
    public KeyValues getHighCardinalityKeyValues(ActionObservationContext context) {
        AgentProcess process = context.getProcess();
        // Full (possibly fully-qualified) action name lives here, where unbounded values are fine.
        KeyValues kv = KeyValues.of(
                SpanAttributes.EMBABEL_RUN_ID, process.getId(),
                SpanAttributes.EMBABEL_ACTION_NAME, context.getAction().getName());
        if (context.getAction() instanceof com.embabel.agent.core.Action coreAction) {
            String input = ObservationUtils.getActionInputs(coreAction, process);
            if (!input.isEmpty()) {
                kv = kv.and(SpanAttributes.INPUT_VALUE, ObservationUtils.truncate(input, maxAttributeLength));
            }
            // The action's declared outputs resolved from the blackboard — its actual product,
            // not the global lastResult() (which may belong to a previous action).
            String output = ObservationUtils.getActionOutputs(coreAction, process);
            if (!output.isEmpty()) {
                String truncated = ObservationUtils.truncate(output, maxAttributeLength);
                kv = kv.and(SpanAttributes.EMBABEL_ACTION_RESULT, truncated).and(SpanAttributes.OUTPUT_VALUE, truncated);
            }
        }
        return kv;
    }
}
