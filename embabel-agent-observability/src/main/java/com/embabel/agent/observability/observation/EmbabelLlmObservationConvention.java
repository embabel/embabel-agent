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

import com.embabel.agent.api.event.LlmRequestEvent;
import com.embabel.agent.api.event.observation.LlmObservationContext;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.GlobalObservationConvention;
import io.micrometer.observation.Observation;

/**
 * Global convention for the {@code embabel.llm} span. Reads request metadata from the
 * {@link LlmRequestEvent} wrapped by the thin {@link LlmObservationContext}.
 *
 * <p>Unlike the sibling conventions, this one takes no {@code maxAttributeLength}: it emits only
 * bounded-length keys (model, provider, ids), no free text. When the billing inspector is wired to
 * capture prompt/response, reintroduce the truncation length then rather than carry it dead now.
 */
public class EmbabelLlmObservationConvention
        implements GlobalObservationConvention<LlmObservationContext> {

    /** OTel GenAI operation name for a chat completion. */
    private static final String OPERATION = "chat";

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof LlmObservationContext;
    }

    @Override
    public String getName() {
        return "embabel.llm";
    }

    @Override
    public String getContextualName(LlmObservationContext context) {
        // OpenTelemetry GenAI semantic convention: span name = "{operation} {model}".
        return OPERATION + " " + context.getRequestEvent().getLlmMetadata().getName();
    }

    @Override
    public KeyValues getLowCardinalityKeyValues(LlmObservationContext context) {
        LlmRequestEvent<?> event = context.getRequestEvent();
        KeyValues kv = KeyValues.of(
                "gen_ai.operation.name", OPERATION,
                "gen_ai.request.model", event.getLlmMetadata().getName(),
                "gen_ai.system", event.getLlmMetadata().getProvider(),
                "embabel.agent.name", event.getAgentProcess().getAgent().getName());
        if (event.getAction() != null) {
            kv = kv.and("embabel.action.name", event.getAction().getName());
        }
        return kv;
    }

    @Override
    public KeyValues getHighCardinalityKeyValues(LlmObservationContext context) {
        LlmRequestEvent<?> event = context.getRequestEvent();
        return KeyValues.of(
                "embabel.run.id", event.getAgentProcess().getId(),
                "embabel.interaction.id", event.getInteraction().getId());
    }
}
