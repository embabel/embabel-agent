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
package com.embabel.agent.observability.metrics;

import com.embabel.agent.api.event.*;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.Usage;
import com.embabel.agent.observability.ObservabilityProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Emits Micrometer business metrics for Embabel Agent processes.
 *
 * <p>Metrics produced:
 * <ul>
 *   <li>{@code embabel.agent.active} (gauge) — number of agents currently running</li>
 *   <li>{@code embabel.agent.errors.total} (counter) — agent failures, tagged by {@code agent}</li>
 *   <li>{@code embabel.llm.tokens.total} (counter) — LLM tokens, tagged by {@code agent} and {@code direction}</li>
 *   <li>{@code embabel.llm.cost.total} (counter) — estimated USD cost, tagged by {@code agent}</li>
 *   <li>{@code embabel.tool.errors.total} (counter) — tool failures, tagged by {@code tool}</li>
 *   <li>{@code embabel.planning.replanning.total} (counter) — replanifications, tagged by {@code agent}</li>
 * </ul>
 *
 * @since 0.3.4
 */
public class EmbabelMetricsEventListener implements AgenticEventListener {

    private static final Logger log = LoggerFactory.getLogger(EmbabelMetricsEventListener.class);

    private final MeterRegistry registry;
    private final ObservabilityProperties properties;
    private final AtomicInteger activeAgents = new AtomicInteger(0);

    /**
     * Creates a new metrics event listener and registers the {@code embabel.agent.active} gauge.
     *
     * @param registry   the Micrometer meter registry to publish metrics to
     * @param properties observability configuration properties controlling metrics emission
     */
    public EmbabelMetricsEventListener(MeterRegistry registry, ObservabilityProperties properties) {
        this.registry = registry;
        this.properties = properties;
        Gauge.builder("embabel.agent.active", activeAgents, AtomicInteger::get)
                .description("Number of agent processes currently running")
                .register(registry);
    }

    /**
     * Dispatches agent process events to the appropriate metric recording method.
     *
     * <p>Increments/decrements the active-agents gauge on creation and terminal events.
     * Records token usage and cost on completion or failure, tool errors on tool responses,
     * and replanning counts on replan requests.
     *
     * @param event the agent process event to handle
     */
    @Override
    public void onProcessEvent(AgentProcessEvent event) {
        if (!properties.isMetricsEnabled()) {
            return;
        }

        switch (event) {
            case AgentProcessCreationEvent e -> activeAgents.incrementAndGet();
            case AgentProcessCompletedEvent e -> {
                activeAgents.decrementAndGet();
                recordTokensAndCost(e.getAgentProcess());
            }
            case AgentProcessFailedEvent e -> {
                activeAgents.decrementAndGet();
                recordAgentError(e.getAgentProcess());
                recordTokensAndCost(e.getAgentProcess());
            }
            case ProcessKilledEvent e -> activeAgents.decrementAndGet();
            case ToolCallResponseEvent e -> recordToolError(e);
            case ReplanRequestedEvent e -> recordReplanning(e.getAgentProcess());
            default -> { }
        }
    }

    /**
     * Increments the {@code embabel.agent.errors.total} counter, tagged with the agent name.
     */
    private void recordAgentError(AgentProcess process) {
        String agentName = process.getAgent().getName();
        Counter.builder("embabel.agent.errors.total")
                .description("Total agent process failures")
                .tag("agent", agentName)
                .register(registry)
                .increment();
    }

    /**
     * Records LLM token usage and estimated cost from the completed agent process.
     *
     * <p>Publishes {@code embabel.llm.tokens.total} counters with {@code direction} tag
     * ({@code input} / {@code output}) and {@code embabel.llm.cost.total} if cost &gt; 0.
     */
    private void recordTokensAndCost(AgentProcess process) {
        String agentName = process.getAgent().getName();
        Usage usage = process.usage();
        if (usage != null) {
            if (usage.getPromptTokens() != null) {
                Counter.builder("embabel.llm.tokens.total")
                        .description("Total LLM tokens consumed")
                        .tag("agent", agentName)
                        .tag("direction", "input")
                        .register(registry)
                        .increment(usage.getPromptTokens());
            }
            if (usage.getCompletionTokens() != null) {
                Counter.builder("embabel.llm.tokens.total")
                        .description("Total LLM tokens consumed")
                        .tag("agent", agentName)
                        .tag("direction", "output")
                        .register(registry)
                        .increment(usage.getCompletionTokens());
            }
        }
        double cost = process.cost();
        if (cost > 0) {
            Counter.builder("embabel.llm.cost.total")
                    .description("Estimated LLM cost in USD")
                    .tag("agent", agentName)
                    .register(registry)
                    .increment(cost);
        }
    }

    /**
     * Increments {@code embabel.tool.errors.total} if the tool call result contains an error.
     * The counter is tagged with the tool name. No-ops when the result is successful.
     */
    private void recordToolError(ToolCallResponseEvent event) {
        Throwable error = extractToolError(event);
        if (error != null) {
            String toolName = event.getRequest().getTool();
            Counter.builder("embabel.tool.errors.total")
                    .description("Total tool call failures")
                    .tag("tool", toolName)
                    .register(registry)
                    .increment();
        }
    }

    /**
     * Increments {@code embabel.planning.replanning.total}, tagged with the agent name.
     */
    private void recordReplanning(AgentProcess process) {
        String agentName = process.getAgent().getName();
        Counter.builder("embabel.planning.replanning.total")
                .description("Total replanning events")
                .tag("agent", agentName)
                .register(registry)
                .increment();
    }

    /**
     * Extracts the error from a Kotlin Result via reflection.
     */
    private Throwable extractToolError(ToolCallResponseEvent event) {
        try {
            java.lang.reflect.Method getResultMethod = null;
            for (java.lang.reflect.Method m : ToolCallResponseEvent.class.getMethods()) {
                if (m.getName().startsWith("getResult") && m.getParameterCount() == 0) {
                    getResultMethod = m;
                    break;
                }
            }
            if (getResultMethod == null) {
                return null;
            }

            Object result = getResultMethod.invoke(event);
            if (result == null) {
                return null;
            }
            if (result instanceof Throwable t) {
                return t;
            }

            try {
                java.lang.reflect.Method exceptionOrNullMethod = result.getClass().getMethod("exceptionOrNull");
                Object error = exceptionOrNullMethod.invoke(result);
                if (error instanceof Throwable t) {
                    return t;
                }
            } catch (NoSuchMethodException e) {
                return null;
            }
        } catch (Exception e) {
            log.trace("Could not extract tool error: {}", e.getMessage());
        }
        return null;
    }
}
