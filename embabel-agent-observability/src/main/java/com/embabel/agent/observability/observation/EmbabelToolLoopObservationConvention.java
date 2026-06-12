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

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ToolLoopObservationContext;
    }

    @Override
    public String getName() {
        return "embabel.tool_loop";
    }

    @Override
    public String getContextualName(ToolLoopObservationContext context) {
        return "tool-loop";
    }

    @Override
    public KeyValues getLowCardinalityKeyValues(ToolLoopObservationContext context) {
        ToolLoopStartEvent event = context.getStartEvent();
        KeyValues kv = KeyValues.of(
                "gen_ai.operation.name", "tool_loop",
                "embabel.agent.name", event.getAgentProcess().getAgent().getName(),
                "embabel.tool_loop.max_iterations", String.valueOf(event.getMaxIterations()));
        if (event.getAction() != null) {
            kv = kv.and("embabel.action.name", event.getAction().getName());
        }
        return kv;
    }

    @Override
    public KeyValues getHighCardinalityKeyValues(ToolLoopObservationContext context) {
        ToolLoopStartEvent event = context.getStartEvent();
        return KeyValues.of(
                "embabel.run.id", event.getAgentProcess().getId(),
                "embabel.interaction.id", event.getInteractionId(),
                "embabel.tool_loop.tool_names", String.join(",", event.getToolNames()),
                "embabel.tool_loop.output_class", event.getOutputClass().getName());
    }
}
