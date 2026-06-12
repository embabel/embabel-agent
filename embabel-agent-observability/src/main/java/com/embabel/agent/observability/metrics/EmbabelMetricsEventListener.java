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
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Emits Micrometer business metrics for Embabel Agent processes.
 *
 * <p>Metrics produced:
 * <ul>
 *   <li>{@code embabel.agent.active} (gauge) — number of agents currently running</li>
 *   <li>{@code embabel.agent.errors.total} (counter) — agent failures, tagged by {@code agent}</li>
 *   <li>{@code embabel.llm.tokens.total} (counter) — LLM tokens, tagged by {@code agent} and {@code direction}</li>
 *   <li>{@code embabel.llm.cost.total} (counter) — estimated USD cost, tagged by {@code agent}</li>
 *   <li>{@code embabel.tool.errors.total} (counter) — tool failures, tagged by {@code tool} and {@code agent}</li>
 *   <li>{@code embabel.planning.replanning.total} (counter) — replanifications, tagged by {@code agent}</li>
 *   <li>{@code embabel.agent.duration} (timer) — agent process duration, tagged by {@code agent} and {@code status}</li>
 *   <li>{@code embabel.llm.requests.total} (counter) — LLM requests, tagged by {@code agent} and {@code model}</li>
 *   <li>{@code embabel.llm.duration} (timer) — LLM call duration, tagged by {@code model} and {@code agent}</li>
 *   <li>{@code embabel.tool.duration} (timer) — tool call duration, tagged by {@code tool} and {@code agent}</li>
 *   <li>{@code embabel.tool.calls.total} (counter) — tool calls, tagged by {@code tool} and {@code agent}</li>
 *   <li>{@code embabel.agent.stuck.total} (counter) — agent stuck events, tagged by {@code agent}</li>
 *   <li>{@code embabel.tool_loop.iterations} (summary) — tool loop iteration counts, tagged by {@code agent}</li>
 * </ul>
 *
 * @since 0.3.4
 */
public class EmbabelMetricsEventListener implements AgenticEventListener {

    private static final Logger log = LoggerFactory.getLogger(EmbabelMetricsEventListener.class);

    private final MeterRegistry registry;
    private final ObservabilityProperties properties;
    private final Set<String> activeProcessIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Instant> creationTimestamps = new ConcurrentHashMap<>();

    /**
     * Creates a new metrics event listener and registers the {@code embabel.agent.active} gauge.
     *
     * @param registry   the Micrometer meter registry to publish metrics to
     * @param properties observability configuration properties controlling metrics emission
     */
    public EmbabelMetricsEventListener(MeterRegistry registry, ObservabilityProperties properties) {
        this.registry = registry;
        this.properties = properties;
        Gauge.builder("embabel.agent.active", activeProcessIds, Set::size)
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
            case AgentProcessCreationEvent e -> {
                activeProcessIds.add(e.getAgentProcess().getId());
                creationTimestamps.put(e.getAgentProcess().getId(), Instant.now());
            }
            case AgentProcessCompletedEvent e -> {
                activeProcessIds.remove(e.getAgentProcess().getId());
                recordTokensAndCost(e.getAgentProcess());
                recordAgentDuration(e.getAgentProcess(), "completed");
            }
            case AgentProcessFailedEvent e -> {
                activeProcessIds.remove(e.getAgentProcess().getId());
                recordAgentError(e.getAgentProcess());
                recordTokensAndCost(e.getAgentProcess());
                recordAgentDuration(e.getAgentProcess(), "failed");
            }
            case ProcessKilledEvent e -> {
                activeProcessIds.remove(e.getAgentProcess().getId());
                creationTimestamps.remove(e.getAgentProcess().getId());
            }
            case ToolCallRequestEvent e -> recordToolCall(e);
            case ToolCallResponseEvent e -> {
                recordToolError(e);
                recordToolDuration(e);
            }
            case LlmRequestEvent e -> recordLlmRequest(e);
            case LlmResponseEvent e -> recordLlmDuration(e);
            case AgentProcessStuckEvent e -> {
                activeProcessIds.remove(e.getAgentProcess().getId());
                recordAgentStuck(e);
            }
            case AgentProcessWaitingEvent e -> activeProcessIds.remove(e.getAgentProcess().getId());
            case AgentProcessPausedEvent e -> activeProcessIds.remove(e.getAgentProcess().getId());
            case ToolLoopCompletedEvent e -> recordToolLoopIterations(e);
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
                    .tag("agent", event.getAgentProcess().getAgent().getName())
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

    private void recordAgentDuration(AgentProcess process, String status) {
        var createdAt = creationTimestamps.remove(process.getId());
        if (createdAt != null) {
            var duration = Duration.between(createdAt, Instant.now());
            Timer.builder("embabel.agent.duration")
                    .description("Agent process duration")
                    .tag("agent", process.getAgent().getName())
                    .tag("status", status)
                    .register(registry)
                    .record(duration);
        }
    }

    @SuppressWarnings("rawtypes")
    private void recordLlmRequest(LlmRequestEvent event) {
        Counter.builder("embabel.llm.requests.total")
                .description("Total LLM requests")
                .tag("agent", event.getAgentProcess().getAgent().getName())
                .tag("model", event.getLlmMetadata().getName())
                .register(registry)
                .increment();
    }

    @SuppressWarnings("rawtypes")
    private void recordLlmDuration(LlmResponseEvent event) {
        Timer.builder("embabel.llm.duration")
                .description("LLM call duration")
                .tag("model", event.getRequest().getLlmMetadata().getName())
                .tag("agent", event.getAgentProcess().getAgent().getName())
                .register(registry)
                .record(event.getRunningTime());
    }

    private void recordToolCall(ToolCallRequestEvent event) {
        Counter.builder("embabel.tool.calls.total")
                .description("Total tool calls")
                .tag("tool", event.getTool())
                .tag("agent", event.getAgentProcess().getAgent().getName())
                .register(registry)
                .increment();
    }

    private void recordToolDuration(ToolCallResponseEvent event) {
        Timer.builder("embabel.tool.duration")
                .description("Tool call duration")
                .tag("tool", event.getRequest().getTool())
                .tag("agent", event.getAgentProcess().getAgent().getName())
                .register(registry)
                .record(event.getRunningTime());
    }

    private void recordAgentStuck(AgentProcessStuckEvent event) {
        Counter.builder("embabel.agent.stuck.total")
                .description("Total agent stuck events")
                .tag("agent", event.getAgentProcess().getAgent().getName())
                .register(registry)
                .increment();
    }

    private void recordToolLoopIterations(ToolLoopCompletedEvent event) {
        DistributionSummary.builder("embabel.tool_loop.iterations")
                .description("Tool loop iteration counts")
                .tag("agent", event.getAgentProcess().getAgent().getName())
                .register(registry)
                .record(event.getTotalIterations());
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
