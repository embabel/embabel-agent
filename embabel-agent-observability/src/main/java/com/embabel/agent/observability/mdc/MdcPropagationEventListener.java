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
package com.embabel.agent.observability.mdc;

import com.embabel.agent.api.event.*;
import com.embabel.agent.observability.ObservabilityProperties;
import org.slf4j.MDC;

/**
 * Propagates Embabel Agent context into SLF4J MDC for log correlation.
 *
 * <p>Sets the following MDC keys:
 * <ul>
 *   <li>{@code embabel.agent.run_id} — the agent process ID</li>
 *   <li>{@code embabel.agent.name} — the agent name</li>
 *   <li>{@code embabel.action.name} — the current action name (during action execution)</li>
 * </ul>
 *
 * <p>This allows filtering and correlating application logs by agent run or action
 * without any manual MDC configuration.
 *
 * @since 0.3.4
 */
public class MdcPropagationEventListener implements AgenticEventListener {

    static final String MDC_RUN_ID = "embabel.agent.run_id";
    static final String MDC_AGENT_NAME = "embabel.agent.name";
    static final String MDC_ACTION_NAME = "embabel.action.name";

    private final ObservabilityProperties properties;

    /**
     * Creates a new MDC propagation event listener.
     *
     * @param properties observability configuration properties controlling MDC propagation
     */
    public MdcPropagationEventListener(ObservabilityProperties properties) {
        this.properties = properties;
    }

    /**
     * Handles agent process events by setting or clearing MDC keys based on the event type.
     *
     * <p>On {@link AgentProcessCreationEvent}, sets {@code run_id} and {@code agent.name}.
     * On {@link ActionExecutionStartEvent}, adds the {@code action.name} key.
     * On {@link ActionExecutionResultEvent}, removes the {@code action.name} key.
     * On terminal events (completed, failed, killed), clears all MDC keys.
     *
     * @param event the agent process event to handle
     */
    @Override
    public void onProcessEvent(AgentProcessEvent event) {
        if (!properties.isMdcPropagation()) {
            return;
        }

        switch (event) {
            case AgentProcessCreationEvent e -> {
                MDC.put(MDC_RUN_ID, e.getAgentProcess().getId());
                MDC.put(MDC_AGENT_NAME, e.getAgentProcess().getAgent().getName());
            }
            case ActionExecutionStartEvent e -> MDC.put(MDC_ACTION_NAME, e.getAction().getName());
            case ActionExecutionResultEvent e -> MDC.remove(MDC_ACTION_NAME);
            case AgentProcessCompletedEvent e -> clearAll();
            case AgentProcessFailedEvent e -> clearAll();
            case ProcessKilledEvent e -> clearAll();
            default -> { }
        }
    }

    /**
     * Removes all Embabel MDC keys ({@code run_id}, {@code agent.name}, {@code action.name}).
     * Called on terminal process events to prevent MDC leaking into unrelated log statements.
     */
    private void clearAll() {
        MDC.remove(MDC_RUN_ID);
        MDC.remove(MDC_AGENT_NAME);
        MDC.remove(MDC_ACTION_NAME);
    }
}
