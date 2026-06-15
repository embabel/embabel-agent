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

import com.embabel.agent.api.event.AbstractAgentProcessEvent;
import com.embabel.agent.api.event.AgentPlatformEvent;
import com.embabel.agent.api.event.AgentProcessCompletedEvent;
import com.embabel.agent.api.event.AgentProcessEvent;
import com.embabel.agent.api.event.AgentProcessFailedEvent;
import com.embabel.agent.api.event.AgentProcessPausedEvent;
import com.embabel.agent.api.event.AgentProcessPlanFormulatedEvent;
import com.embabel.agent.api.event.AgentProcessReadyToPlanEvent;
import com.embabel.agent.api.event.AgentProcessStuckEvent;
import com.embabel.agent.api.event.AgentProcessWaitingEvent;
import com.embabel.agent.api.event.AgenticEventListener;
import com.embabel.agent.api.event.DynamicAgentCreationEvent;
import com.embabel.agent.api.event.EmbeddingEvent;
import com.embabel.agent.api.event.EmbeddingEventListener;
import com.embabel.agent.api.event.EmbeddingInvocationEvent;
import com.embabel.agent.api.event.EmbeddingResponseEvent;
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
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.EarlyTermination;
import com.embabel.agent.core.EmbeddingInvocation;
import com.embabel.agent.core.LlmInvocation;
import com.embabel.agent.core.ToolGroupMetadata;
import com.embabel.agent.core.Usage;
import com.embabel.agent.event.AgentProcessRagEvent;
import com.embabel.agent.event.RagResponseEvent;
import com.embabel.agent.observability.ObservabilityProperties;
import com.embabel.agent.rag.service.QualityMetrics;
import com.embabel.agent.rag.service.RagRequest;
import com.embabel.agent.rag.service.RagResponse;
import com.embabel.common.ai.model.EmbeddingServiceMetadata;
import com.embabel.common.core.types.SimilarityResult;
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
public class EmbabelSpanEventListener implements AgenticEventListener, EmbeddingEventListener {

    private final ObservationRegistry registry;
    private final ObservabilityProperties properties;

    /**
     * Per-run plan iteration counter, so the planning span can carry {@code embabel.plan.iteration}
     * and {@code embabel.plan.is_replanning} without the core tracking it. Entries are removed when
     * the run reaches a terminal lifecycle state (completed/failed/killed/terminated), so the map
     * stays bounded. Suspended-but-resumable states (waiting/paused/stuck) keep their entry so the
     * counter survives a resume.
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
            case AgentProcessReadyToPlanEvent e -> {
                if (properties.isTracePlanning()) {
                    recordReadyToPlan(e);
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
                if (properties.isTraceToolLoop() && properties.isTraceToolLoopCompleted()) {
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
            case ProcessKilledEvent e -> {
                planIterations.remove(e.getAgentProcess().getId());
                recordLifecycle(e);
            }
            case EarlyTermination e -> {
                planIterations.remove(e.getAgentProcess().getId());
                recordLifecycle(e);
            }
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
        // Deliberately NOT a GenAI "generation": the Spring AI ChatModel span already carries the
        // gen_ai.operation.name and is the billable generation (prompt/completion + usage). Emitting
        // gen_ai.operation.name here too would make exporters (e.g. Langfuse) count the same call as
        // two generations. We keep this span as a plain cost/usage record (model + tokens + cost still
        // visible) without re-triggering the generation classification.
        Observation observation = point(SpanAttributes.EMBABEL_LLM_INVOCATION,
                "llm.invocation " + invocation.getLlmMetadata().getName())
                .lowCardinalityKeyValue(SpanAttributes.EMBABEL_EVENT_TYPE, "llm_invocation")
                .lowCardinalityKeyValue(SpanAttributes.EMBABEL_LLM_MODEL, invocation.getLlmMetadata().getName())
                .highCardinalityKeyValue(SpanAttributes.EMBABEL_INTERACTION_ID, event.getInteractionId());
        addUsageAndCost(observation, invocation.getUsage(), invocation.cost());
        emit(observation);
    }

    private void recordEmbeddingInvocation(EmbeddingInvocationEvent event) {
        EmbeddingInvocation invocation = event.getInvocation();
        emitEmbeddingSpan(invocation.getEmbeddingMetadata(), invocation.getUsage(),
                invocation.cost(), event.getInteractionId());
    }

    /**
     * Standalone (non-agent) embedding channel: RAG/pgvector calls made outside an agent process
     * reach observability only via {@link EmbeddingEventListener}, not the {@code onProcessEvent}
     * channel that carries {@link EmbeddingInvocationEvent}. Bridge those into the same
     * {@code embabel.embedding} span so embeddings show up in the trace regardless of caller.
     *
     * <p>When an agent process <em>is</em> active, both channels fire for the same call — but the
     * agent channel already emits the span via {@link #recordEmbeddingInvocation}, so we skip here
     * to avoid a duplicate. {@code AgentProcess.get() != null} is exactly the "in-agent" predicate
     * {@code EmbeddingOperations} uses to decide whether to dispatch the agent-channel event.
     */
    @Override
    public void onEmbeddingEvent(EmbeddingEvent event) {
        if (registry.isNoop() || !properties.isTraceEmbedding()) {
            return;
        }
        if (AgentProcess.get() != null || !(event instanceof EmbeddingResponseEvent response)) {
            return;
        }
        EmbeddingInvocation invocation = new EmbeddingInvocation(
                response.getEmbeddingMetadata(), response.getUsage(),
                null, response.getTimestamp(), response.getRunningTime());
        emitEmbeddingSpan(invocation.getEmbeddingMetadata(), invocation.getUsage(),
                invocation.cost(), response.getId());
    }

    private void emitEmbeddingSpan(EmbeddingServiceMetadata metadata, Usage usage,
                                   double cost, String interactionId) {
        Observation observation = point(SpanAttributes.EMBABEL_EMBEDDING, "embeddings " + metadata.getName())
                .lowCardinalityKeyValue(SpanAttributes.EMBABEL_EVENT_TYPE, "embedding")
                .lowCardinalityKeyValue(SpanAttributes.GEN_AI_OPERATION_NAME, "embeddings")
                .lowCardinalityKeyValue(SpanAttributes.GEN_AI_REQUEST_MODEL, metadata.getName())
                .highCardinalityKeyValue(SpanAttributes.EMBABEL_INTERACTION_ID, interactionId);
        addUsageAndCost(observation, usage, cost);
        emit(observation);
    }

    /** Marks the start of planning: the world state the planner sees, captured as the span input. */
    private void recordReadyToPlan(AgentProcessReadyToPlanEvent event) {
        Observation observation = point(SpanAttributes.EMBABEL_PLANNING_READY, "planning ready")
                .lowCardinalityKeyValue(SpanAttributes.EMBABEL_EVENT_TYPE, "planning_ready")
                .lowCardinalityKeyValue(SpanAttributes.GEN_AI_OPERATION_NAME, "planning");
        if (properties.isCaptureMessageContent()) {
            observation.highCardinalityKeyValue(SpanAttributes.INPUT_VALUE,
                    truncate(event.getWorldState().infoString(true, 0)));
        }
        emit(observation);
    }

    private void recordPlanning(AgentProcessPlanFormulatedEvent event) {
        int iteration = planIterations.merge(event.getAgentProcess().getId(), 1, Integer::sum);
        String goalName = event.getPlan().getGoal().getName();
        String goalShort = ObservationUtils.shortName(goalName);
        Observation observation = point(SpanAttributes.EMBABEL_PLANNING, "planning " + goalShort)
                .lowCardinalityKeyValue(SpanAttributes.EMBABEL_EVENT_TYPE, "planning")
                .lowCardinalityKeyValue(SpanAttributes.GEN_AI_OPERATION_NAME, "planning")
                .lowCardinalityKeyValue(SpanAttributes.EMBABEL_PLAN_GOAL, goalName)
                .lowCardinalityKeyValue(SpanAttributes.EMBABEL_PLAN_GOAL_SHORT, goalShort)
                .lowCardinalityKeyValue(SpanAttributes.EMBABEL_PLAN_IS_REPLANNING, String.valueOf(iteration > 1))
                .highCardinalityKeyValue(SpanAttributes.EMBABEL_PLAN_ITERATION, String.valueOf(iteration))
                .highCardinalityKeyValue(SpanAttributes.EMBABEL_PLAN_ACTION_COUNT,
                        String.valueOf(event.getPlan().getActions().size()));
        if (properties.isCaptureMessageContent()) {
            observation
                    .highCardinalityKeyValue(SpanAttributes.INPUT_VALUE,
                            truncate(event.getWorldState().infoString(true, 0)))
                    .highCardinalityKeyValue(SpanAttributes.OUTPUT_VALUE,
                            truncate(ObservationUtils.formatPlanSteps(event.getPlan())));
        }
        emit(observation);
    }

    private void recordReplan(ReplanRequestedEvent event) {
        Observation observation = point(SpanAttributes.EMBABEL_REPLAN, "replan")
                .lowCardinalityKeyValue(SpanAttributes.EMBABEL_EVENT_TYPE, "replan")
                .lowCardinalityKeyValue(SpanAttributes.GEN_AI_OPERATION_NAME, "replan");
        if (properties.isCaptureMessageContent()) {
            observation.highCardinalityKeyValue(SpanAttributes.EMBABEL_REPLAN_REASON, truncate(event.getReason()));
        }
        emit(observation);
    }

    private void recordRag(RagResponseEvent event) {
        RagResponse response = event.getRagResponse();
        RagRequest request = response.getRequest();
        Observation observation = point(SpanAttributes.EMBABEL_RAG, "rag " + response.getService())
                .lowCardinalityKeyValue(SpanAttributes.EMBABEL_EVENT_TYPE, "rag")
                .lowCardinalityKeyValue(SpanAttributes.GEN_AI_OPERATION_NAME, "rag")
                .lowCardinalityKeyValue(SpanAttributes.EMBABEL_RAG_SERVICE, response.getService())
                .lowCardinalityKeyValue(SpanAttributes.EMBABEL_RAG_TOP_K, String.valueOf(request.getTopK()))
                .lowCardinalityKeyValue(SpanAttributes.EMBABEL_RAG_SIMILARITY_THRESHOLD,
                        String.valueOf(request.getSimilarityThreshold()))
                .highCardinalityKeyValue(SpanAttributes.EMBABEL_RAG_RESULT_COUNT,
                        String.valueOf(response.getResults().size()));
        if (properties.isCaptureMessageContent()) {
            observation.highCardinalityKeyValue(SpanAttributes.EMBABEL_RAG_QUERY, truncate(request.getQuery()));
        }

        response.getResults().stream()
                .mapToDouble(SimilarityResult::getScore)
                .max()
                .ifPresent(topScore -> observation.highCardinalityKeyValue(
                        SpanAttributes.EMBABEL_RAG_TOP_SCORE, String.valueOf(topScore)));

        QualityMetrics metrics = response.getQualityMetrics();
        if (metrics != null) {
            observation
                    .highCardinalityKeyValue(SpanAttributes.EMBABEL_RAG_RAGAS_SCORE,
                            String.valueOf(metrics.getOverallScore()))
                    .highCardinalityKeyValue(SpanAttributes.EMBABEL_RAG_FAITHFULNESS,
                            String.valueOf(metrics.getFaithfulness()))
                    .highCardinalityKeyValue(SpanAttributes.EMBABEL_RAG_ANSWER_RELEVANCY,
                            String.valueOf(metrics.getAnswerRelevancy()))
                    .highCardinalityKeyValue(SpanAttributes.EMBABEL_RAG_CONTEXT_PRECISION,
                            String.valueOf(metrics.getContextPrecision()))
                    .highCardinalityKeyValue(SpanAttributes.EMBABEL_RAG_CONTEXT_RECALL,
                            String.valueOf(metrics.getContextRecall()))
                    .highCardinalityKeyValue(SpanAttributes.EMBABEL_RAG_CONTEXT_RELEVANCY,
                            String.valueOf(metrics.getContextRelevancy()));
        }
        emit(observation);
    }

    private void recordStateTransition(StateTransitionEvent event) {
        Observation observation = point(SpanAttributes.EMBABEL_STATE_TRANSITION,
                "state " + event.getNewState().getClass().getSimpleName())
                .lowCardinalityKeyValue(SpanAttributes.EMBABEL_EVENT_TYPE, "state_transition")
                .lowCardinalityKeyValue(SpanAttributes.GEN_AI_OPERATION_NAME, "state_transition")
                .lowCardinalityKeyValue(SpanAttributes.EMBABEL_STATE_TO, event.getNewState().getClass().getSimpleName())
                .lowCardinalityKeyValue(SpanAttributes.EMBABEL_STATE_FROM,
                        event.getPreviousState() == null ? "none" : event.getPreviousState().getClass().getSimpleName());
        emit(observation);
    }

    private void recordLifecycle(AbstractAgentProcessEvent event) {
        if (!properties.isTraceLifecycleStates()) {
            return;
        }
        Observation observation = point(SpanAttributes.EMBABEL_LIFECYCLE, event.getAgentProcess().getStatus().name())
                .lowCardinalityKeyValue(SpanAttributes.EMBABEL_EVENT_TYPE, "lifecycle")
                .lowCardinalityKeyValue(SpanAttributes.GEN_AI_OPERATION_NAME, "lifecycle")
                .lowCardinalityKeyValue(SpanAttributes.EMBABEL_LIFECYCLE_STATE, event.getAgentProcess().getStatus().name());
        emit(observation);
    }

    private void recordDynamicAgentCreation(DynamicAgentCreationEvent event) {
        Observation observation = point(SpanAttributes.EMBABEL_DYNAMIC_AGENT_CREATION,
                "dynamic_agent " + event.getAgent().getName())
                .lowCardinalityKeyValue(SpanAttributes.EMBABEL_EVENT_TYPE, "dynamic_agent_creation")
                .lowCardinalityKeyValue(SpanAttributes.GEN_AI_OPERATION_NAME, "dynamic_agent_creation")
                .lowCardinalityKeyValue(SpanAttributes.EMBABEL_AGENT_NAME, event.getAgent().getName())
                .highCardinalityKeyValue(SpanAttributes.EMBABEL_DYNAMIC_AGENT_BASIS, String.valueOf(event.getBasis()));
        emit(observation);
    }

    private void recordRanking(RankingChoiceMadeEvent<?> event) {
        Observation observation = point(SpanAttributes.EMBABEL_RANKING,
                "ranking " + event.getChoice().getMatch().getName())
                .lowCardinalityKeyValue(SpanAttributes.EMBABEL_EVENT_TYPE, "ranking")
                .lowCardinalityKeyValue(SpanAttributes.GEN_AI_OPERATION_NAME, "ranking")
                .lowCardinalityKeyValue(SpanAttributes.EMBABEL_RANKING_CHOICE, event.getChoice().getMatch().getName())
                .highCardinalityKeyValue(SpanAttributes.EMBABEL_RANKING_SCORE,
                        String.valueOf(event.getChoice().getScore()))
                .highCardinalityKeyValue(SpanAttributes.EMBABEL_RANKING_OPTION_COUNT,
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
        Observation observation = point(SpanAttributes.EMBABEL_TOOL, "execute_tool " + request.getTool())
                .lowCardinalityKeyValue(SpanAttributes.EMBABEL_EVENT_TYPE, "tool_call")
                .lowCardinalityKeyValue(SpanAttributes.GEN_AI_OPERATION_NAME, "execute_tool")
                .lowCardinalityKeyValue(SpanAttributes.GEN_AI_TOOL_NAME, request.getTool())
                .lowCardinalityKeyValue(SpanAttributes.GEN_AI_TOOL_TYPE, "function")
                .lowCardinalityKeyValue(SpanAttributes.EMBABEL_TOOL_NAME, request.getTool())
                .highCardinalityKeyValue(SpanAttributes.EMBABEL_TOOL_DURATION_MS,
                        String.valueOf(event.getRunningTime().toMillis()));
        String correlationId = request.getCorrelationId();
        if (correlationId != null && !"-".equals(correlationId)) {
            observation.highCardinalityKeyValue(SpanAttributes.EMBABEL_TOOL_CORRELATION_ID, correlationId);
        }
        ToolGroupMetadata metadata = request.getToolGroupMetadata();
        if (metadata != null) {
            observation.lowCardinalityKeyValue(SpanAttributes.EMBABEL_TOOL_GROUP_NAME, metadata.getName());
            observation.lowCardinalityKeyValue(SpanAttributes.EMBABEL_TOOL_GROUP_ROLE, metadata.getRole());
            String description = metadata.getDescription();
            if (description != null && !description.isEmpty()) {
                observation.highCardinalityKeyValue(SpanAttributes.GEN_AI_TOOL_DESCRIPTION, truncate(description));
            }
        }
        if (request.getToolInput() != null && properties.isCaptureMessageContent()) {
            observation.highCardinalityKeyValue(SpanAttributes.GEN_AI_TOOL_CALL_ARGUMENTS, truncate(request.getToolInput()));
        }

        observation.start();
        Throwable error = ToolCallOutcomes.error(event);
        if (error != null) {
            observation.lowCardinalityKeyValue(SpanAttributes.EMBABEL_TOOL_STATUS, "error");
            observation.highCardinalityKeyValue(SpanAttributes.EMBABEL_TOOL_ERROR_TYPE, error.getClass().getSimpleName());
            observation.highCardinalityKeyValue(SpanAttributes.EMBABEL_TOOL_ERROR_MESSAGE, truncate(error.getMessage()));
            observation.error(error);
        } else {
            observation.lowCardinalityKeyValue(SpanAttributes.EMBABEL_TOOL_STATUS, "success");
            String result = ToolCallOutcomes.resultText(event);
            if (result != null && properties.isCaptureMessageContent()) {
                observation.highCardinalityKeyValue(SpanAttributes.GEN_AI_TOOL_CALL_RESULT, truncate(result));
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
        Observation observation = point(SpanAttributes.EMBABEL_TOOL_LOOP_COMPLETED, "tool-loop-completed")
                .lowCardinalityKeyValue(SpanAttributes.EMBABEL_EVENT_TYPE, "tool_loop_completed")
                .lowCardinalityKeyValue(SpanAttributes.GEN_AI_OPERATION_NAME, "tool_loop")
                .lowCardinalityKeyValue(SpanAttributes.EMBABEL_TOOL_LOOP_REPLAN_REQUESTED,
                        String.valueOf(event.getReplanRequested()))
                .highCardinalityKeyValue(SpanAttributes.EMBABEL_INTERACTION_ID, event.getInteractionId())
                .highCardinalityKeyValue(SpanAttributes.EMBABEL_TOOL_LOOP_TOTAL_ITERATIONS,
                        String.valueOf(event.getTotalIterations()))
                .highCardinalityKeyValue(SpanAttributes.EMBABEL_TOOL_LOOP_DURATION_MS,
                        String.valueOf(event.getRunningTime().toMillis()));
        emit(observation);
    }

    private void recordGoalAchieved(GoalAchievedEvent event) {
        String goalName = event.getGoal().getName();
        Observation observation = point(SpanAttributes.EMBABEL_GOAL, "goal " + ObservationUtils.shortName(goalName))
                .lowCardinalityKeyValue(SpanAttributes.EMBABEL_EVENT_TYPE, "goal")
                .lowCardinalityKeyValue(SpanAttributes.GEN_AI_OPERATION_NAME, "goal_achieved")
                .lowCardinalityKeyValue(SpanAttributes.EMBABEL_GOAL_NAME, goalName)
                .lowCardinalityKeyValue(SpanAttributes.EMBABEL_GOAL_SHORT_NAME, ObservationUtils.shortName(goalName));
        if (properties.isCaptureMessageContent()) {
            observation.highCardinalityKeyValue(SpanAttributes.INPUT_VALUE,
                    truncate(event.getWorldState().infoString(true, 0)));
            Object result = event.getAgentProcess().lastResult();
            if (result != null) {
                observation.highCardinalityKeyValue(SpanAttributes.EMBABEL_GOAL_RESULT, truncate(result.toString()));
            }
        }
        emit(observation);
    }

    /** Ranking that produced no choice: a span marked errored, so the failure is visible in the trace. */
    private void recordRankingCouldNotBeMade(RankingChoiceCouldNotBeMadeEvent<?> event) {
        Observation observation = point(SpanAttributes.EMBABEL_RANKING, "ranking")
                .lowCardinalityKeyValue(SpanAttributes.EMBABEL_EVENT_TYPE, "ranking")
                .lowCardinalityKeyValue(SpanAttributes.GEN_AI_OPERATION_NAME, "ranking")
                .lowCardinalityKeyValue(SpanAttributes.EMBABEL_RANKING_CHOICE, "none")
                .highCardinalityKeyValue(SpanAttributes.EMBABEL_RANKING_TYPE, event.getType().getSimpleName())
                .highCardinalityKeyValue(SpanAttributes.EMBABEL_RANKING_CONFIDENCE_CUTOFF,
                        String.valueOf(event.getConfidenceCutOff()))
                .highCardinalityKeyValue(SpanAttributes.EMBABEL_RANKING_OPTION_COUNT,
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
                observation.highCardinalityKeyValue(SpanAttributes.GEN_AI_USAGE_INPUT_TOKENS, String.valueOf(usage.getPromptTokens()));
            }
            if (usage.getCompletionTokens() != null) {
                observation.highCardinalityKeyValue(SpanAttributes.GEN_AI_USAGE_OUTPUT_TOKENS, String.valueOf(usage.getCompletionTokens()));
            }
            if (usage.getTotalTokens() != null) {
                observation.highCardinalityKeyValue(SpanAttributes.GEN_AI_USAGE_TOTAL_TOKENS, String.valueOf(usage.getTotalTokens()));
            }
        }
        if (cost > 0.0) {
            observation.highCardinalityKeyValue(SpanAttributes.EMBABEL_LLM_COST, String.valueOf(cost));
        }
    }

    /**
     * Start a point observation, parented under the current observation. Centralizing the
     * {@code parentObservation(registry.getCurrentObservation())} call guarantees every point span
     * nests under the live agent/action/tool-loop/LLM span instead of becoming an orphan root —
     * a mistake easy to make by omission when each {@code record*} method wires it by hand.
     */
    private Observation point(String name, String contextualName) {
        return Observation.createNotStarted(name, registry)
                .parentObservation(registry.getCurrentObservation())
                .contextualName(contextualName);
    }

    /** Open and immediately close the observation: a point span marking the completed invocation. */
    private static void emit(Observation observation) {
        observation.start();
        observation.stop();
    }
}
