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

import com.embabel.agent.api.event.LlmRequestEvent;
import com.embabel.agent.api.event.observation.LlmObservationContext;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.GlobalObservationConvention;
import io.micrometer.observation.Observation;

/**
 * Global convention for the {@code embabel.llm} span: the core's scoped wrapper around one LLM
 * call. Reads request metadata from the {@link LlmRequestEvent} wrapped by the thin
 * {@link LlmObservationContext}.
 *
 * <p><strong>Deliberately not a GenAI generation.</strong> The actual generation — prompt/completion
 * content and token usage — is carried by the nested Spring AI ChatModel span (enriched with the
 * {@code gen_ai.*} semantic convention by {@code ChatModelObservationFilter}). If this span also
 * advertised {@code gen_ai.operation.name=chat} / a {@code "chat {model}"} name, GenAI consumers
 * (LangSmith, Langfuse, …) would count a single call as two generations — an empty one here plus the
 * real one below. So this span stays a plain structural wrapper named {@code "llm {model}"}, carrying
 * only embabel correlation metadata (agent/action/run/interaction ids); the model is recorded under
 * the embabel-namespaced {@code embabel.llm.model} for convenience, not as {@code gen_ai.request.model}.
 *
 * <p>It emits only bounded-length keys (model, ids), no free text, hence takes no
 * {@code maxAttributeLength}.
 */
public class EmbabelLlmObservationConvention
        implements GlobalObservationConvention<LlmObservationContext> {

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof LlmObservationContext;
    }

    @Override
    public String getName() {
        return SpanAttributes.EMBABEL_LLM;
    }

    @Override
    public String getContextualName(LlmObservationContext context) {
        // Embabel "<type> <name>" scheme (cf. "agent X", "action Y"): a wrapper name, NOT the GenAI
        // "{operation} {model}" generation name — that belongs to the nested ChatModel span.
        return "llm " + context.getRequestEvent().getLlmMetadata().getName();
    }

    @Override
    public KeyValues getLowCardinalityKeyValues(LlmObservationContext context) {
        LlmRequestEvent<?> event = context.getRequestEvent();
        // No gen_ai.* here on purpose: see the class javadoc (avoids a duplicate empty generation).
        KeyValues kv = KeyValues.of(
                SpanAttributes.EMBABEL_LLM_MODEL, event.getLlmMetadata().getName(),
                SpanAttributes.EMBABEL_AGENT_NAME, event.getAgentProcess().getAgent().getName());
        // Only the bounded short_name is a LOW-cardinality tag; the full (possibly fully-qualified)
        // action name is unbounded and goes to HIGH-cardinality below.
        if (event.getAction() != null) {
            kv = kv.and(SpanAttributes.EMBABEL_ACTION_SHORT_NAME,
                    ObservationUtils.shortName(event.getAction().getName()));
        }
        return kv;
    }

    @Override
    public KeyValues getHighCardinalityKeyValues(LlmObservationContext context) {
        LlmRequestEvent<?> event = context.getRequestEvent();
        KeyValues kv = KeyValues.of(
                SpanAttributes.EMBABEL_RUN_ID, event.getAgentProcess().getId(),
                SpanAttributes.EMBABEL_INTERACTION_ID, event.getInteraction().getId());
        if (event.getAction() != null) {
            kv = kv.and(SpanAttributes.EMBABEL_ACTION_NAME, event.getAction().getName());
        }
        return kv;
    }
}
