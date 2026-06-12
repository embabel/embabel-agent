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

import com.embabel.agent.api.event.AbstractAgentProcessEvent;
import com.embabel.agent.api.event.AgentPlatformEvent;
import com.embabel.agent.api.event.AgentProcessCompletedEvent;
import com.embabel.agent.api.event.AgentProcessEvent;
import com.embabel.agent.api.event.AgentProcessFailedEvent;
import com.embabel.agent.api.event.AgentProcessPausedEvent;
import com.embabel.agent.api.event.AgentProcessPlanFormulatedEvent;
import com.embabel.agent.api.event.AgentProcessStuckEvent;
import com.embabel.agent.api.event.AgentProcessWaitingEvent;
import com.embabel.agent.api.event.AgenticEventListener;
import com.embabel.agent.api.event.DynamicAgentCreationEvent;
import com.embabel.agent.api.event.EmbeddingInvocationEvent;
import com.embabel.agent.api.event.GoalAchievedEvent;
import com.embabel.agent.api.event.LlmInvocationEvent;
import com.embabel.agent.api.event.ProcessKilledEvent;
import com.embabel.agent.api.event.RankingChoiceCouldNotBeMadeEvent;
import com.embabel.agent.api.event.RankingChoiceMadeEvent;
import com.embabel.agent.api.event.ReplanRequestedEvent;
import com.embabel.agent.api.event.StateTransitionEvent;
import com.embabel.agent.api.event.ToolCallRequestEvent;
import com.embabel.agent.api.event.ToolCallResponseEvent;
import com.embabel.agent.api.event.ToolLoopCompletedEvent;
import com.embabel.agent.api.event.observation.ToolCallOutcomes;
import com.embabel.agent.core.EmbeddingInvocation;
import com.embabel.agent.core.LlmInvocation;
import com.embabel.agent.core.ToolGroupMetadata;
import com.embabel.agent.core.Usage;
import com.embabel.agent.event.AgentProcessRagEvent;
import com.embabel.agent.event.RagResponseEvent;
import com.embabel.agent.observability.ObservabilityProperties;
import com.embabel.agent.rag.service.RagResponse;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Emits instantaneous spans for <em>point events</em> — events that carry no held scope and fire
 * synchronously on the work thread, so they nest safely under the current observation without any
 * core involvement (unlike the long-scoped agent/action/tool-loop/LLM spans, instrumented directly
 * at the work site). Each span is gated by its {@code trace-*} switch, keeping those flags live.
 */
public class EmbabelSpanEventListener implements AgenticEventListener {

    private final ObservationRegistry registry;
    private final ObservabilityProperties properties;

    /**
     * Per-run plan iteration counter, so the planning span can carry {@code embabel.plan.iteration}
     * and {@code embabel.plan.is_replanning} without the core tracking it. Entries are removed when
     * the run reaches a terminal lifecycle state (completed/failed/killed), so the map stays bounded.
     */
    private final Map<String, Integer> planIterations = new ConcurrentHashMap<>();

    public EmbabelSpanEventListener(ObservationRegistry registry, ObservabilityProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    @Override
    public void onProcessEvent(AgentProcessEvent event) {
        if (registry.isNoop()) {
            return;
        }
        switch (event) {
            case LlmInvocationEvent e -> {
                if (properties.isTraceLlmCalls()) {
                    recordLlmInvocation(e);
                }
            }
            case EmbeddingInvocationEvent e -> {
                if (properties.isTraceEmbedding()) {
                    recordEmbeddingInvocation(e);
                }
            }
            case AgentProcessPlanFormulatedEvent e -> {
                if (properties.isTracePlanning()) {
                    recordPlanning(e);
                }
            }
            case ReplanRequestedEvent e -> {
                if (properties.isTracePlanning()) {
                    recordReplan(e);
                }
            }
            case AgentProcessRagEvent e -> {
                if (properties.isTraceRag() && e.getRagEvent() instanceof RagResponseEvent response) {
                    recordRag(response);
                }
            }
            case StateTransitionEvent e -> {
                if (properties.isTraceStateTransitions()) {
                    recordStateTransition(e);
                }
            }
            case ToolCallResponseEvent e -> {
                if (properties.isTraceToolCalls()) {
                    recordToolCall(e);
                }
            }
            case ToolLoopCompletedEvent e -> {
                if (properties.isTraceToolLoop()) {
                    recordToolLoopCompleted(e);
                }
            }
            case GoalAchievedEvent e -> {
                if (properties.isTraceLifecycleStates()) {
                    recordGoalAchieved(e);
                }
            }
            case AgentProcessCompletedEvent e -> {
                planIterations.remove(e.getAgentProcess().getId());
                recordLifecycle(e);
            }
            case AgentProcessFailedEvent e -> {
                planIterations.remove(e.getAgentProcess().getId());
                recordLifecycle(e);
            }
            case AgentProcessWaitingEvent e -> recordLifecycle(e);
            case AgentProcessPausedEvent e -> recordLifecycle(e);
            case AgentProcessStuckEvent e -> recordLifecycle(e);
            case ProcessKilledEvent e -> planIterations.remove(e.getAgentProcess().getId());
            default -> {
            }
        }
    }

    /**
     * Ranking (agent/route selection) is a platform-level event with no agent process, so it has no
     * enclosing observation and produces a root span.
     */
    @Override
    public void onPlatformEvent(AgentPlatformEvent event) {
        if (registry.isNoop()) {
            return;
        }
        if (event instanceof RankingChoiceMadeEvent<?> e && properties.isTraceRanking()) {
            recordRanking(e);
        } else if (event instanceof RankingChoiceCouldNotBeMadeEvent<?> e && properties.isTraceRanking()) {
            recordRankingCouldNotBeMade(e);
        } else if (event instanceof DynamicAgentCreationEvent e && properties.isTraceDynamicAgentCreation()) {
            recordDynamicAgentCreation(e);
        }
    }

    private void recordLlmInvocation(LlmInvocationEvent event) {
        LlmInvocation invocation = event.getInvocation();
        Observation observation = Observation.createNotStarted("embabel.llm.invocation", registry)
                .parentObservation(registry.getCurrentObservation())
                .lowCardinalityKeyValue("gen_ai.operation.name", "chat")
                .lowCardinalityKeyValue("gen_ai.request.model", invocation.getLlmMetadata().getName())
                .highCardinalityKeyValue("embabel.interaction.id", event.getInteractionId());
        addUsageAndCost(observation, invocation.getUsage(), invocation.cost());
        emit(observation);
    }

    private void recordEmbeddingInvocation(EmbeddingInvocationEvent event) {
        EmbeddingInvocation invocation = event.getInvocation();
        Observation observation = Observation.createNotStarted("embabel.embedding", registry)
                .parentObservation(registry.getCurrentObservation())
                .lowCardinalityKeyValue("gen_ai.operation.name", "embeddings")
                .lowCardinalityKeyValue("gen_ai.request.model", invocation.getEmbeddingMetadata().getName())
                .highCardinalityKeyValue("embabel.interaction.id", event.getInteractionId());
        addUsageAndCost(observation, invocation.getUsage(), invocation.cost());
        emit(observation);
    }

    private void recordPlanning(AgentProcessPlanFormulatedEvent event) {
        int iteration = planIterations.merge(event.getAgentProcess().getId(), 1, Integer::sum);
        Observation observation = Observation.createNotStarted("embabel.planning", registry)
                .parentObservation(registry.getCurrentObservation())
                .lowCardinalityKeyValue("gen_ai.operation.name", "planning")
                .lowCardinalityKeyValue("embabel.plan.goal", event.getPlan().getGoal().getName())
                .lowCardinalityKeyValue("embabel.plan.is_replanning", String.valueOf(iteration > 1))
                .highCardinalityKeyValue("embabel.plan.iteration", String.valueOf(iteration))
                .highCardinalityKeyValue("embabel.plan.action_count",
                        String.valueOf(event.getPlan().getActions().size()));
        emit(observation);
    }

    private void recordReplan(ReplanRequestedEvent event) {
        Observation observation = Observation.createNotStarted("embabel.replan", registry)
                .parentObservation(registry.getCurrentObservation())
                .lowCardinalityKeyValue("gen_ai.operation.name", "replan")
                .highCardinalityKeyValue("embabel.replan.reason", truncate(event.getReason()));
        emit(observation);
    }

    private void recordRag(RagResponseEvent event) {
        RagResponse response = event.getRagResponse();
        Observation observation = Observation.createNotStarted("embabel.rag", registry)
                .parentObservation(registry.getCurrentObservation())
                .lowCardinalityKeyValue("gen_ai.operation.name", "rag")
                .lowCardinalityKeyValue("embabel.rag.service", response.getService())
                .highCardinalityKeyValue("embabel.rag.query", response.getRequest().getQuery())
                .highCardinalityKeyValue("embabel.rag.result_count",
                        String.valueOf(response.getResults().size()));
        emit(observation);
    }

    private void recordStateTransition(StateTransitionEvent event) {
        Observation observation = Observation.createNotStarted("embabel.state_transition", registry)
                .parentObservation(registry.getCurrentObservation())
                .lowCardinalityKeyValue("gen_ai.operation.name", "state_transition")
                .lowCardinalityKeyValue("embabel.state.to", event.getNewState().getClass().getSimpleName())
                .lowCardinalityKeyValue("embabel.state.from",
                        event.getPreviousState() == null ? "none" : event.getPreviousState().getClass().getSimpleName());
        emit(observation);
    }

    private void recordLifecycle(AbstractAgentProcessEvent event) {
        if (!properties.isTraceLifecycleStates()) {
            return;
        }
        Observation observation = Observation.createNotStarted("embabel.lifecycle", registry)
                .parentObservation(registry.getCurrentObservation())
                .lowCardinalityKeyValue("gen_ai.operation.name", "lifecycle")
                .lowCardinalityKeyValue("embabel.lifecycle.state", event.getAgentProcess().getStatus().name());
        emit(observation);
    }

    private void recordDynamicAgentCreation(DynamicAgentCreationEvent event) {
        Observation observation = Observation.createNotStarted("embabel.dynamic_agent_creation", registry)
                .parentObservation(registry.getCurrentObservation())
                .lowCardinalityKeyValue("gen_ai.operation.name", "dynamic_agent_creation")
                .lowCardinalityKeyValue("embabel.agent.name", event.getAgent().getName())
                .highCardinalityKeyValue("embabel.dynamic_agent.basis", String.valueOf(event.getBasis()));
        emit(observation);
    }

    private void recordRanking(RankingChoiceMadeEvent<?> event) {
        Observation observation = Observation.createNotStarted("embabel.ranking", registry)
                .parentObservation(registry.getCurrentObservation())
                .lowCardinalityKeyValue("gen_ai.operation.name", "ranking")
                .lowCardinalityKeyValue("embabel.ranking.choice", event.getChoice().getMatch().getName())
                .highCardinalityKeyValue("embabel.ranking.score",
                        String.valueOf(event.getChoice().getScore()))
                .highCardinalityKeyValue("embabel.ranking.option_count",
                        String.valueOf(event.getChoices().size()));
        emit(observation);
    }

    /**
     * Tool call span (request → response). Emitted on the response event — which fires synchronously
     * while the enclosing observation (tool loop / LLM) is still current — so it nests correctly with
     * no held scope. Spring AI's native {@code tool call} span is suppressed by the tier filter to
     * avoid a duplicate; this one carries Embabel's richer metadata (correlation id, tool group,
     * status, error, duration, arguments/result).
     */
    private void recordToolCall(ToolCallResponseEvent event) {
        ToolCallRequestEvent request = event.getRequest();
        Observation observation = Observation.createNotStarted("embabel.tool", registry)
                .parentObservation(registry.getCurrentObservation())
                .lowCardinalityKeyValue("gen_ai.operation.name", "execute_tool")
                .lowCardinalityKeyValue("gen_ai.tool.name", request.getTool())
                .lowCardinalityKeyValue("embabel.tool.name", request.getTool())
                .highCardinalityKeyValue("embabel.tool.duration_ms",
                        String.valueOf(event.getRunningTime().toMillis()));
        String correlationId = request.getCorrelationId();
        if (correlationId != null && !"-".equals(correlationId)) {
            observation.highCardinalityKeyValue("embabel.tool.correlation_id", correlationId);
        }
        ToolGroupMetadata metadata = request.getToolGroupMetadata();
        if (metadata != null) {
            observation.lowCardinalityKeyValue("embabel.tool.group.name", metadata.getName());
            observation.lowCardinalityKeyValue("embabel.tool.group.role", metadata.getRole());
        }
        if (request.getToolInput() != null) {
            observation.highCardinalityKeyValue("gen_ai.tool.call.arguments", truncate(request.getToolInput()));
        }

        observation.start();
        Throwable error = ToolCallOutcomes.error(event);
        if (error != null) {
            observation.lowCardinalityKeyValue("embabel.tool.status", "error");
            observation.highCardinalityKeyValue("embabel.tool.error.type", error.getClass().getSimpleName());
            observation.highCardinalityKeyValue("embabel.tool.error.message", truncate(error.getMessage()));
            observation.error(error);
        } else {
            observation.lowCardinalityKeyValue("embabel.tool.status", "success");
            String result = ToolCallOutcomes.resultText(event);
            if (result != null) {
                observation.highCardinalityKeyValue("gen_ai.tool.call.result", truncate(result));
            }
        }
        observation.stop();
    }

    /**
     * Tool loop outcome (iteration count, replan flag). The scoped {@code embabel.tool_loop} span has
     * already closed when this completion event fires, so the outcome is recorded as a sibling point
     * span rather than enriching the closed span.
     */
    private void recordToolLoopCompleted(ToolLoopCompletedEvent event) {
        Observation observation = Observation.createNotStarted("embabel.tool_loop.completed", registry)
                .parentObservation(registry.getCurrentObservation())
                .lowCardinalityKeyValue("gen_ai.operation.name", "tool_loop")
                .lowCardinalityKeyValue("embabel.tool_loop.replan_requested",
                        String.valueOf(event.getReplanRequested()))
                .highCardinalityKeyValue("embabel.interaction.id", event.getInteractionId())
                .highCardinalityKeyValue("embabel.tool_loop.total_iterations",
                        String.valueOf(event.getTotalIterations()))
                .highCardinalityKeyValue("embabel.tool_loop.duration_ms",
                        String.valueOf(event.getRunningTime().toMillis()));
        emit(observation);
    }

    private void recordGoalAchieved(GoalAchievedEvent event) {
        Observation observation = Observation.createNotStarted("embabel.goal", registry)
                .parentObservation(registry.getCurrentObservation())
                .lowCardinalityKeyValue("gen_ai.operation.name", "goal_achieved")
                .lowCardinalityKeyValue("embabel.goal.name", event.getGoal().getName());
        Object result = event.getAgentProcess().lastResult();
        if (result != null) {
            observation.highCardinalityKeyValue("embabel.goal.result", truncate(result.toString()));
        }
        emit(observation);
    }

    /** Ranking that produced no choice: a span marked errored, so the failure is visible in the trace. */
    private void recordRankingCouldNotBeMade(RankingChoiceCouldNotBeMadeEvent<?> event) {
        Observation observation = Observation.createNotStarted("embabel.ranking", registry)
                .parentObservation(registry.getCurrentObservation())
                .lowCardinalityKeyValue("gen_ai.operation.name", "ranking")
                .lowCardinalityKeyValue("embabel.ranking.choice", "none")
                .highCardinalityKeyValue("embabel.ranking.type", event.getType().getSimpleName())
                .highCardinalityKeyValue("embabel.ranking.confidence_cutoff",
                        String.valueOf(event.getConfidenceCutOff()))
                .highCardinalityKeyValue("embabel.ranking.option_count",
                        String.valueOf(event.getChoices().size()));
        observation.start();
        observation.error(new IllegalStateException("No ranking choice could be made"));
        observation.stop();
    }

    private String truncate(String value) {
        return ObservationUtils.truncate(value, properties.getMaxAttributeLength());
    }

    private static void addUsageAndCost(Observation observation, Usage usage, double cost) {
        if (usage != null) {
            if (usage.getPromptTokens() != null) {
                observation.highCardinalityKeyValue("gen_ai.usage.input_tokens", String.valueOf(usage.getPromptTokens()));
            }
            if (usage.getCompletionTokens() != null) {
                observation.highCardinalityKeyValue("gen_ai.usage.output_tokens", String.valueOf(usage.getCompletionTokens()));
            }
            if (usage.getTotalTokens() != null) {
                observation.highCardinalityKeyValue("gen_ai.usage.total_tokens", String.valueOf(usage.getTotalTokens()));
            }
        }
        if (cost > 0.0) {
            observation.highCardinalityKeyValue("embabel.llm.cost", String.valueOf(cost));
        }
    }

    /** Open and immediately close the observation: a point span marking the completed invocation. */
    private static void emit(Observation observation) {
        observation.start();
        observation.stop();
    }
}
