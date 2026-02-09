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

import com.embabel.agent.api.event.*;
import com.embabel.agent.core.Action;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.ToolGroupMetadata;
import com.embabel.agent.event.AgentProcessRagEvent;
import com.embabel.agent.event.RagEvent;
import com.embabel.agent.event.RagRequestReceivedEvent;
import com.embabel.agent.event.RagResponseEvent;
import com.embabel.agent.observability.ObservabilityProperties;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.agent.rag.pipeline.event.*;
import com.embabel.agent.spi.Ranking;
import com.embabel.plan.Plan;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Traces Embabel Agent events via Spring Observation API.
 * Provides traces (custom handler) and metrics (automatic).
 *
 * @author Quantpulsar 2025-2026
 */
public class EmbabelFullObservationEventListener implements AgenticEventListener {

    private static final Logger log = LoggerFactory.getLogger(EmbabelFullObservationEventListener.class);

    private final ObservationRegistry observationRegistry;
    private final ObservabilityProperties properties;

    // Active observations keyed by type:runId:name
    private final Map<String, ObservationContext> activeObservations = new ConcurrentHashMap<>();
    private final Map<String, String> inputSnapshots = new ConcurrentHashMap<>();
    private final Map<String, Integer> planIterations = new ConcurrentHashMap<>();

    /** Holds Observation and its Scope. */
    private record ObservationContext(Observation observation, Observation.Scope scope) {}

    /**
     * Creates the listener with required dependencies.
     *
     * @param observationRegistry the Spring Observation registry for creating spans
     * @param properties the observability configuration properties
     */
    public EmbabelFullObservationEventListener(
            ObservationRegistry observationRegistry,
            ObservabilityProperties properties) {
        this.observationRegistry = observationRegistry;
        this.properties = properties;
        log.info("EmbabelFullObservationEventListener initialized with Spring Observation API");
    }

    /** Routes incoming events to the appropriate handler methods. */
    @Override
    public void onProcessEvent(@NotNull AgentProcessEvent event) {
        switch (event) {
            case AgentProcessCreationEvent e -> onAgentProcessCreation(e);
            case AgentProcessCompletedEvent e -> onAgentProcessCompleted(e);
            case AgentProcessFailedEvent e -> onAgentProcessFailed(e);
            case ActionExecutionStartEvent e -> onActionStart(e);
            case ActionExecutionResultEvent e -> onActionResult(e);
            case GoalAchievedEvent e -> onGoalAchieved(e);
            case ToolCallRequestEvent e -> {
                if (properties.isTraceToolCalls()) onToolCallRequest(e);
            }
            case ToolCallResponseEvent e -> {
                if (properties.isTraceToolCalls()) onToolCallResponse(e);
            }
            case AgentProcessReadyToPlanEvent e -> {
                if (properties.isTracePlanning()) onReadyToPlan(e);
            }
            case AgentProcessPlanFormulatedEvent e -> {
                if (properties.isTracePlanning()) onPlanFormulated(e);
            }
            case StateTransitionEvent e -> {
                if (properties.isTraceStateTransitions()) onStateTransition(e);
            }
            case AgentProcessWaitingEvent e -> {
                if (properties.isTraceLifecycleStates()) onLifecycleState(e, "WAITING");
            }
            case AgentProcessPausedEvent e -> {
                if (properties.isTraceLifecycleStates()) onLifecycleState(e, "PAUSED");
            }
            case AgentProcessStuckEvent e -> {
                if (properties.isTraceLifecycleStates()) onStuck(e);
            }
            case ToolLoopStartEvent e -> {
                if (properties.isTraceToolLoop()) onToolLoopStart(e);
            }
            case ToolLoopCompletedEvent e -> {
                if (properties.isTraceToolLoop()) onToolLoopCompleted(e);
            }
            case LlmRequestEvent<?> e -> {
                if (properties.isTraceLlmCalls()) onLlmRequest(e);
            }
            case LlmResponseEvent<?> e -> {
                if (properties.isTraceLlmCalls()) onLlmResponse(e);
            }
            case AgentProcessRagEvent e -> {
                if (properties.isTraceRag()) onRagEvent(e);
            }
            case ReplanRequestedEvent e -> {
                if (properties.isTracePlanning()) onReplanRequested(e);
            }
            case ProcessKilledEvent e -> onProcessKilled(e);
            default -> {}
        }
    }

    /** Routes incoming platform events to the appropriate handler methods. */
    @Override
    public void onPlatformEvent(@NotNull AgentPlatformEvent event) {
        switch (event) {
            case RankingChoiceMadeEvent<?> e -> {
                if (properties.isTraceRanking()) onRankingChoiceMade(e);
            }
            case RankingChoiceCouldNotBeMadeEvent<?> e -> {
                if (properties.isTraceRanking()) onRankingChoiceCouldNotBeMade(e);
            }
            case RankingChoiceRequestEvent<?> e -> {
                if (properties.isTraceRanking()) onRankingRequest(e);
            }
            case DynamicAgentCreationEvent e -> {
                if (properties.isTraceDynamicAgentCreation()) onDynamicAgentCreation(e);
            }
            default -> {}
        }
    }

    // --- Agent Lifecycle ---

    /** Starts a new observation span when an agent process is created. */
    private void onAgentProcessCreation(AgentProcessCreationEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String agentName = process.getAgent().getName();
        String parentId = process.getParentId();
        boolean isSubagent = parentId != null && !parentId.isEmpty();

        String goalName = ObservationUtils.extractGoalName(process);
        String plannerType = process.getProcessOptions().getPlannerType().name();
        String input = ObservationUtils.getBlackboardSnapshot(process);

        // Root or subagent context
        EmbabelObservationContext context = isSubagent
                ? EmbabelObservationContext.subAgent(runId, agentName, parentId)
                : EmbabelObservationContext.rootAgent(runId, agentName);

        // Create observation
        Observation observation = Observation.createNotStarted(agentName, () -> context, observationRegistry);

        // For subagents, set parent observation from parent's active action (or agent as fallback)
        if (isSubagent) {
            ObservationContext parentCtx = findParentObservation(parentId);
            if (parentCtx != null) {
                observation.parentObservation(parentCtx.observation);
            }
        }

        // OpenTelemetry GenAI semantic conventions
        observation.lowCardinalityKeyValue("gen_ai.operation.name", "agent");
        observation.highCardinalityKeyValue("gen_ai.conversation.id", runId);

        // Low cardinality (metrics)
        observation.lowCardinalityKeyValue("embabel.agent.name", agentName);
        observation.lowCardinalityKeyValue("embabel.agent.is_subagent", String.valueOf(isSubagent));
        observation.lowCardinalityKeyValue("embabel.agent.planner_type", plannerType);
        observation.lowCardinalityKeyValue("embabel.event.type", "agent_process");

        // High cardinality (traces)
        observation.highCardinalityKeyValue("embabel.agent.run_id", runId);
        observation.highCardinalityKeyValue("embabel.agent.goal", goalName);
        observation.highCardinalityKeyValue("embabel.agent.parent_id", parentId != null ? parentId : "");

        if (!input.isEmpty()) {
            observation.highCardinalityKeyValue("input.value", truncate(input));
            inputSnapshots.put(ObservationKeys.agentKey(runId), input);
        }

        // Init plan counter
        planIterations.put(runId, 0);

        // Start and open scope
        observation.start();
        Observation.Scope scope = observation.openScope();

        activeObservations.put(ObservationKeys.agentKey(runId), new ObservationContext(observation, scope));
        log.debug("Started observation for agent: {} (runId: {})", agentName, runId);
    }

    /** Closes the observation span when agent completes successfully. */
    private void onAgentProcessCompleted(AgentProcessCompletedEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        var key = ObservationKeys.agentKey(runId);

        ObservationContext ctx = activeObservations.remove(key);
        inputSnapshots.remove(key);
        planIterations.remove(runId);

        if (ctx != null) {
            ctx.observation.lowCardinalityKeyValue("embabel.agent.status", "completed");

            String output = ObservationUtils.getBlackboardSnapshot(process);
            if (!output.isEmpty()) {
                ctx.observation.highCardinalityKeyValue("output.value", truncate(output));
            }

            Object lastResult = process.getBlackboard().lastResult();
            if (lastResult != null) {
                ctx.observation.highCardinalityKeyValue("embabel.agent.result", truncate(lastResult.toString()));
            }

            // Close and stop
            ctx.scope.close();
            ctx.observation.stop();

            log.debug("Completed observation for agent runId: {}", runId);
        }
    }

    /** Closes the observation span with error status when agent fails. */
    private void onAgentProcessFailed(AgentProcessFailedEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        var key = ObservationKeys.agentKey(runId);

        ObservationContext ctx = activeObservations.remove(key);
        inputSnapshots.remove(key);
        planIterations.remove(runId);

        if (ctx != null) {
            ctx.observation.lowCardinalityKeyValue("embabel.agent.status", "failed");

            Object failureInfo = process.getFailureInfo();
            if (failureInfo != null) {
                ctx.observation.highCardinalityKeyValue("embabel.agent.error", truncate(failureInfo.toString()));
                ctx.observation.error(new RuntimeException(truncate(failureInfo.toString())));
            }

            ctx.scope.close();
            ctx.observation.stop();

            log.debug("Failed observation for agent runId: {}", runId);
        }
    }

    // --- Actions ---

    /** Starts a new observation span when an action begins execution. */
    private void onActionStart(ActionExecutionStartEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        Action action = event.getAction();
        String actionName = action.getName();
        String shortName = action.shortName();
        String input = ObservationUtils.getActionInputs(action, process);

        EmbabelObservationContext context = EmbabelObservationContext.action(runId, shortName);

        Observation observation = Observation.createNotStarted(shortName, () -> context, observationRegistry);

        // OpenTelemetry GenAI semantic convention
        observation.lowCardinalityKeyValue("gen_ai.operation.name", "execute_action");
        observation.lowCardinalityKeyValue("embabel.event.type", "action");

        observation.lowCardinalityKeyValue("embabel.action.short_name", shortName);
        observation.highCardinalityKeyValue("embabel.action.name", actionName);
        observation.highCardinalityKeyValue("embabel.action.run_id", runId);
        observation.highCardinalityKeyValue("embabel.action.description", event.getAction().getDescription());

        var key = ObservationKeys.actionKey(runId, actionName);
        if (!input.isEmpty()) {
            observation.highCardinalityKeyValue("input.value", truncate(input));
            inputSnapshots.put(key, input);
        }

        observation.start();
        Observation.Scope scope = observation.openScope();

        activeObservations.put(key, new ObservationContext(observation, scope));
        log.debug("Started observation for action: {} (runId: {})", shortName, runId);
    }

    /** Closes the action observation span with execution result. */
    private void onActionResult(ActionExecutionResultEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String actionName = event.getAction().getName();
        var key = ObservationKeys.actionKey(runId, actionName);

        ObservationContext ctx = activeObservations.remove(key);
        inputSnapshots.remove(key);

        if (ctx != null) {
            String statusName = event.getActionStatus().getStatus().name();
            ctx.observation.lowCardinalityKeyValue("embabel.action.status", statusName);
            ctx.observation.highCardinalityKeyValue("embabel.action.duration_ms",
                    String.valueOf(event.getRunningTime().toMillis()));

            String output = ObservationUtils.getBlackboardSnapshot(process);
            if (!output.isEmpty()) {
                ctx.observation.highCardinalityKeyValue("output.value", truncate(output));
            }

            Object lastResult = process.getBlackboard().lastResult();
            if (lastResult != null) {
                ctx.observation.highCardinalityKeyValue("embabel.action.result", truncate(lastResult.toString()));
            }

            if ("FAILED".equals(statusName)) {
                ctx.observation.error(new RuntimeException("Action failed: " + actionName));
            }

            ctx.scope.close();
            ctx.observation.stop();

            log.debug("Completed observation for action: {} (runId: {})", actionName, runId);
        }
    }

    // --- Goals ---

    /** Records an instant observation event when a goal is achieved. */
    private void onGoalAchieved(GoalAchievedEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String goalName = event.getGoal().getName();

        String shortGoalName = goalName.contains(".")
                ? goalName.substring(goalName.lastIndexOf('.') + 1)
                : goalName;

        EmbabelObservationContext context = EmbabelObservationContext.goal(runId, "goal:" + shortGoalName);

        Observation observation = Observation.createNotStarted("goal:" + shortGoalName, () -> context, observationRegistry);

        observation.lowCardinalityKeyValue("embabel.goal.short_name", shortGoalName);
        observation.highCardinalityKeyValue("embabel.goal.name", goalName);
        observation.highCardinalityKeyValue("embabel.event.type", "goal_achieved");

        String snapshot = ObservationUtils.getBlackboardSnapshot(process);
        if (!snapshot.isEmpty()) {
            observation.highCardinalityKeyValue("input.value", truncate(snapshot));
        }

        Object lastResult = process.getBlackboard().lastResult();
        if (lastResult != null) {
            observation.highCardinalityKeyValue("output.value", truncate(lastResult.toString()));
        }

        // Instant event
        observation.start();
        observation.stop();

        log.debug("Recorded goal achieved: {} (runId: {})", shortGoalName, runId);
    }

    // --- Tools ---

    /**
     * Starts a new observation span when a tool call is initiated.
     * Uses current observation as parent to integrate with Spring AI ChatClient.
     */
    private void onToolCallRequest(ToolCallRequestEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String toolName = event.getTool();

        // Get current observation (could be Spring AI ChatClient observation)
        // This ensures tool calls are nested under LLM calls
        Observation parentObservation = observationRegistry.getCurrentObservation();

        var toolSpan = ObservationKeys.toolSpanName(toolName);
        EmbabelObservationContext context = EmbabelObservationContext.toolCall(runId, toolSpan);

        Observation observation = Observation.createNotStarted(toolSpan, () -> context, observationRegistry);

        // Set parent observation explicitly for proper hierarchy
        if (parentObservation != null) {
            observation.parentObservation(parentObservation);
        }

        // OpenTelemetry GenAI semantic conventions for tool execution
        observation.lowCardinalityKeyValue("gen_ai.operation.name", "execute_tool");
        observation.lowCardinalityKeyValue("gen_ai.tool.name", toolName);
        observation.lowCardinalityKeyValue("gen_ai.tool.type", "function");

        observation.lowCardinalityKeyValue("embabel.tool.name", toolName);
        observation.lowCardinalityKeyValue("embabel.event.type", "tool_call");

        // Add correlation ID for tracing tool calls across systems
        String correlationId = event.getCorrelationId();
        if (correlationId != null && !"-".equals(correlationId)) {
            observation.highCardinalityKeyValue("embabel.tool.correlation_id", correlationId);
        }

        // Add tool group metadata if available (description, role, etc.)
        ToolGroupMetadata metadata = event.getToolGroupMetadata();
        if (metadata != null) {
            String description = metadata.getDescription();
            if (description != null && !description.isEmpty()) {
                observation.highCardinalityKeyValue("gen_ai.tool.description", truncate(description));
            }
            String groupName = metadata.getName();
            if (groupName != null) {
                observation.lowCardinalityKeyValue("embabel.tool.group.name", groupName);
            }
            String role = metadata.getRole();
            if (role != null) {
                observation.lowCardinalityKeyValue("embabel.tool.group.role", role);
            }
        }

        if (event.getToolInput() != null) {
            String truncatedInput = truncate(event.getToolInput());
            observation.highCardinalityKeyValue("input.value", truncatedInput);
            observation.highCardinalityKeyValue("gen_ai.tool.call.arguments", truncatedInput);
        }

        observation.start();
        Observation.Scope scope = observation.openScope();

        activeObservations.put(ObservationKeys.toolKey(runId, toolName), new ObservationContext(observation, scope));
        log.debug("Started observation for tool: {} (runId: {}, correlationId: {}, parentObservation: {})",
                toolName, runId, correlationId,
                parentObservation != null ? parentObservation.getContext().getName() : "none");
    }

    /**
     * Closes the tool call observation span with response data.
     * Captures tool output from blackboard.lastResult().
     */
    private void onToolCallResponse(ToolCallResponseEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String toolName = event.getRequest().getTool();
        var key = ObservationKeys.toolKey(runId, toolName);

        ObservationContext ctx = activeObservations.remove(key);

        if (ctx != null) {
            ctx.observation.highCardinalityKeyValue("embabel.tool.duration_ms",
                    String.valueOf(event.getRunningTime().toMillis()));

            // Extract tool result using reflection (Kotlin Result has mangled method names)
            Object toolResult = ObservationUtils.extractToolResult(event);
            if (toolResult != null) {
                String truncatedResult = truncate(toolResult.toString());
                ctx.observation.highCardinalityKeyValue("output.value", truncatedResult);
                ctx.observation.highCardinalityKeyValue("gen_ai.tool.call.result", truncatedResult);
                ctx.observation.lowCardinalityKeyValue("embabel.tool.status", "success");
            } else {
                // Check if there was an error
                Throwable error = ObservationUtils.extractToolError(event);
                if (error != null) {
                    ctx.observation.lowCardinalityKeyValue("embabel.tool.status", "error");
                    ctx.observation.highCardinalityKeyValue("embabel.tool.error.type", error.getClass().getSimpleName());
                    ctx.observation.highCardinalityKeyValue("embabel.tool.error.message", truncate(error.getMessage()));
                    ctx.observation.error(error);
                } else {
                    ctx.observation.lowCardinalityKeyValue("embabel.tool.status", "success");
                }
            }

            ctx.scope.close();
            ctx.observation.stop();

            log.debug("Completed observation for tool: {} (runId: {})", toolName, runId);
        }
    }

    // --- Planning ---

    /** Records an instant observation when the agent is ready to plan. */
    private void onReadyToPlan(AgentProcessReadyToPlanEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String plannerType = process.getProcessOptions().getPlannerType().name();

        EmbabelObservationContext context = EmbabelObservationContext.planning(runId, "planning:ready");

        Observation observation = Observation.createNotStarted("planning:ready", () -> context, observationRegistry);

        // OpenTelemetry GenAI semantic convention
        observation.lowCardinalityKeyValue("gen_ai.operation.name", "planning");
        observation.lowCardinalityKeyValue("embabel.event.type", "planning_ready");

        observation.lowCardinalityKeyValue("embabel.plan.planner_type", plannerType);
        observation.highCardinalityKeyValue("embabel.agent.run_id", runId);

        if (event.getWorldState() != null) {
            observation.highCardinalityKeyValue("input.value", truncate(event.getWorldState().infoString(true, 0)));
        }

        observation.start();
        observation.stop();

        log.debug("Recorded planning ready event (runId: {})", runId);
    }

    /** Records an instant observation when a plan is formulated or replanned. */
    private void onPlanFormulated(AgentProcessPlanFormulatedEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        Plan plan = event.getPlan();

        int iteration = planIterations.compute(runId, (k, v) -> v == null ? 1 : v + 1);
        boolean isReplanning = iteration > 1;

        String spanName = isReplanning ? "planning:replanning" : "planning:formulated";

        EmbabelObservationContext context = EmbabelObservationContext.planning(runId, spanName);

        Observation observation = Observation.createNotStarted(spanName, () -> context, observationRegistry);

        // OpenTelemetry GenAI semantic convention
        observation.lowCardinalityKeyValue("gen_ai.operation.name", isReplanning ? "replanning" : "planning");
        observation.lowCardinalityKeyValue("embabel.event.type", isReplanning ? "replanning" : "plan_formulated");

        observation.lowCardinalityKeyValue("embabel.plan.is_replanning", String.valueOf(isReplanning));
        observation.lowCardinalityKeyValue("embabel.plan.planner_type", process.getProcessOptions().getPlannerType().name());
        observation.highCardinalityKeyValue("embabel.agent.run_id", runId);
        observation.highCardinalityKeyValue("embabel.plan.iteration", String.valueOf(iteration));

        if (plan != null) {
            observation.highCardinalityKeyValue("embabel.plan.actions_count", String.valueOf(plan.getActions().size()));
            observation.highCardinalityKeyValue("output.value", truncate(ObservationUtils.formatPlanSteps(plan)));
            if (plan.getGoal() != null) {
                observation.highCardinalityKeyValue("embabel.plan.goal", plan.getGoal().getName());
            }
        }

        if (event.getWorldState() != null) {
            observation.highCardinalityKeyValue("input.value", truncate(event.getWorldState().infoString(true, 0)));
        }

        observation.start();
        observation.stop();

        log.debug("Recorded plan formulated event (iteration: {}, runId: {})", iteration, runId);
    }

    private void onReplanRequested(ReplanRequestedEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();

        EmbabelObservationContext context = EmbabelObservationContext.planning(runId, "planning:replan_requested");

        Observation observation = Observation.createNotStarted("planning:replan_requested", () -> context, observationRegistry);

        observation.lowCardinalityKeyValue("embabel.event.type", "replan_requested");
        observation.highCardinalityKeyValue("embabel.agent.run_id", runId);
        observation.highCardinalityKeyValue("embabel.replan.reason", truncate(event.getReason()));

        observation.start();
        observation.stop();

        log.debug("Recorded replan requested event (runId: {}, reason: {})", runId, event.getReason());
    }

    // --- State Transitions ---

    /** Records an instant observation when the agent transitions to a new state. */
    private void onStateTransition(StateTransitionEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        Object newState = event.getNewState();

        String stateName = newState != null ? newState.getClass().getSimpleName() : "Unknown";

        EmbabelObservationContext context = EmbabelObservationContext.stateTransition(runId, "state:" + stateName);

        Observation observation = Observation.createNotStarted("state:" + stateName, () -> context, observationRegistry);

        observation.lowCardinalityKeyValue("embabel.state.to", stateName);
        observation.lowCardinalityKeyValue("embabel.event.type", "state_transition");
        observation.highCardinalityKeyValue("embabel.agent.run_id", runId);

        if (newState != null) {
            observation.highCardinalityKeyValue("input.value", truncate(newState.toString()));
        }

        observation.start();
        observation.stop();

        log.debug("Recorded state transition to: {} (runId: {})", stateName, runId);
    }

    // --- Lifecycle States ---

    /** Records an instant observation for lifecycle state changes (waiting, paused, stuck). */
    private void onLifecycleState(AbstractAgentProcessEvent event, String state) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();

        EmbabelObservationContext context = EmbabelObservationContext.lifecycle(runId, "lifecycle:" + state.toLowerCase());

        Observation observation = Observation.createNotStarted("lifecycle:" + state.toLowerCase(), () -> context, observationRegistry);

        observation.lowCardinalityKeyValue("embabel.lifecycle.state", state);
        observation.highCardinalityKeyValue("embabel.agent.run_id", runId);
        observation.highCardinalityKeyValue("embabel.event.type", "lifecycle_" + state.toLowerCase());

        String snapshot = ObservationUtils.getBlackboardSnapshot(process);
        if (!snapshot.isEmpty()) {
            observation.highCardinalityKeyValue("input.value", truncate(snapshot));
        }

        observation.start();
        observation.stop();

        log.debug("Recorded lifecycle state: {} (runId: {})", state, runId);
    }

    private void onStuck(AgentProcessStuckEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();

        EmbabelObservationContext context = EmbabelObservationContext.lifecycle(runId, "lifecycle:stuck");

        Observation observation = Observation.createNotStarted("lifecycle:stuck", () -> context, observationRegistry);

        observation.lowCardinalityKeyValue("embabel.lifecycle.state", "STUCK");
        observation.highCardinalityKeyValue("embabel.agent.run_id", runId);
        observation.highCardinalityKeyValue("embabel.event.type", "lifecycle_stuck");

        String snapshot = ObservationUtils.getBlackboardSnapshot(process);
        if (!snapshot.isEmpty()) {
            observation.highCardinalityKeyValue("input.value", truncate(snapshot));
        }

        observation.start();
        observation.error(new RuntimeException("Agent process is stuck"));
        observation.stop();

        log.debug("Recorded lifecycle state: STUCK (runId: {})", runId);
    }

    // --- Tool Loop ---

    /**
     * Starts an observation for tool loop execution, parented under the Action (or Agent if no action).
     * Opens a scope so the Micrometer observation and Spring AI calls become children.
     */
    private void onToolLoopStart(ToolLoopStartEvent event) {
        AgentProcess process = event.getAgentProcess();
        var runId = process.getId();
        var actionName = event.getAction() != null ? event.getAction().getName() : "__no_action__";
        var interactionId = event.getInteractionId();

        // Find parent: prefer LLM observation (by interactionId), then action, fallback to agent.
        // interactionId is shared between LlmRequestEvent and ToolLoopStartEvent and is unique per LLM call.
        // This works even when events fire on different threads (e.g., CompletableFuture.supplyAsync).
        var llmKey = ObservationKeys.llmKey(runId, interactionId);
        ObservationContext llmCtx = activeObservations.get(llmKey);
        String resolvedParent;
        Observation parentObs;
        if (llmCtx != null) {
            parentObs = llmCtx.observation;
            resolvedParent = "llm (key=" + llmKey + ")";
        } else {
            var actionKey = ObservationKeys.actionKey(runId, actionName);
            ObservationContext parentCtx = activeObservations.get(actionKey);
            if (parentCtx != null) {
                parentObs = parentCtx.observation;
                resolvedParent = "action (key=" + actionKey + ")";
            } else {
                var agentKey = ObservationKeys.agentKey(runId);
                ObservationContext agentCtx = activeObservations.get(agentKey);
                parentObs = agentCtx != null ? agentCtx.observation : null;
                resolvedParent = agentCtx != null ? "agent (key=" + agentKey + ")" : "NONE";
            }
        }
        log.debug("Tool loop parent resolution: {} (runId: {}, action: {}, interactionId: {}, activeKeys: {})",
                resolvedParent, runId, actionName, interactionId, activeObservations.keySet());

        var toolLoopName = ObservationKeys.toolLoopSpanName(interactionId);
        EmbabelObservationContext context = EmbabelObservationContext.toolLoop(runId, toolLoopName);

        Observation observation = Observation.createNotStarted(toolLoopName, () -> context, observationRegistry);

        if (parentObs != null) {
            observation.parentObservation(parentObs);
        } else {
            log.warn("No parent found for tool loop {} (runId: {}). Tool loop will be an independent trace.", interactionId, runId);
        }

        observation.lowCardinalityKeyValue("gen_ai.operation.name", "tool_loop");
        observation.lowCardinalityKeyValue("embabel.event.type", "tool_loop");
        observation.highCardinalityKeyValue("embabel.tool_loop.interaction_id", interactionId);
        observation.highCardinalityKeyValue("embabel.tool_loop.max_iterations", String.valueOf(event.getMaxIterations()));
        observation.highCardinalityKeyValue("embabel.tool_loop.output_class", event.getOutputClass().getSimpleName());
        observation.highCardinalityKeyValue("embabel.tool_loop.tools", String.join(", ", event.getToolNames()));

        observation.start();
        Observation.Scope scope = observation.openScope();

        activeObservations.put(ObservationKeys.toolLoopKey(runId, interactionId), new ObservationContext(observation, scope));
        log.debug("Started observation for tool loop: {} (runId: {})", interactionId, runId);
    }

    /**
     * Completes the tool loop observation with iteration count and replan status.
     */
    private void onToolLoopCompleted(ToolLoopCompletedEvent event) {
        AgentProcess process = event.getAgentProcess();
        var runId = process.getId();
        var interactionId = event.getInteractionId();
        var key = ObservationKeys.toolLoopKey(runId, interactionId);

        ObservationContext ctx = activeObservations.remove(key);

        if (ctx != null) {
            ctx.observation.highCardinalityKeyValue("embabel.tool_loop.total_iterations",
                    String.valueOf(event.getTotalIterations()));
            ctx.observation.highCardinalityKeyValue("embabel.tool_loop.replan_requested",
                    String.valueOf(event.getReplanRequested()));
            ctx.observation.highCardinalityKeyValue("embabel.tool_loop.duration_ms",
                    String.valueOf(event.getRunningTime().toMillis()));
            ctx.scope.close();
            ctx.observation.stop();
            log.debug("Completed observation for tool loop: {} (runId: {})", interactionId, runId);
        }
    }

    // --- LLM Calls ---

    /**
     * Starts an observation for an LLM call, parented under the Action observation (or Agent if no action).
     * By opening a scope, the subsequent tool-loop and Spring AI ChatModel observations
     * become children of this LLM observation instead of the Action.
     */
    private void onLlmRequest(LlmRequestEvent<?> event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String actionName = event.getAction() != null ? event.getAction().getName() : "__no_action__";
        String modelName = event.getLlmMetadata().getName();

        // Find parent: prefer action observation, fallback to agent observation
        ObservationContext parentCtx = activeObservations.get(ObservationKeys.actionKey(runId, actionName));
        if (parentCtx == null) {
            parentCtx = activeObservations.get(ObservationKeys.agentKey(runId));
        }

        var llmSpanName = ObservationKeys.LLM_PREFIX + modelName;
        EmbabelObservationContext context = EmbabelObservationContext.llmCall(runId, llmSpanName);

        Observation observation = Observation.createNotStarted(llmSpanName, () -> context, observationRegistry);

        if (parentCtx != null) {
            observation.parentObservation(parentCtx.observation);
        }

        observation.lowCardinalityKeyValue("gen_ai.operation.name", "chat");
        observation.lowCardinalityKeyValue("gen_ai.request.model", modelName);
        observation.lowCardinalityKeyValue("embabel.event.type", "llm_call");
        observation.highCardinalityKeyValue("embabel.llm.output_class", event.getOutputClass().getSimpleName());
        observation.highCardinalityKeyValue("embabel.llm.interaction_id", event.getInteraction().getId());

        // GenAI hyperparameters from LlmOptions
        LlmOptions llmOptions = event.getInteraction().getLlm();
        if (llmOptions.getTemperature() != null) {
            observation.highCardinalityKeyValue("gen_ai.request.temperature", String.valueOf(llmOptions.getTemperature()));
        }
        if (llmOptions.getMaxTokens() != null) {
            observation.highCardinalityKeyValue("gen_ai.request.max_tokens", String.valueOf(llmOptions.getMaxTokens()));
        }
        if (llmOptions.getTopP() != null) {
            observation.highCardinalityKeyValue("gen_ai.request.top_p", String.valueOf(llmOptions.getTopP()));
        }
        // Provider from LlmMetadata (low cardinality - few distinct providers)
        String provider = event.getLlmMetadata().getProvider();
        if (provider != null && !provider.isEmpty()) {
            observation.lowCardinalityKeyValue("gen_ai.provider.name", provider);
        }

        // Capture LLM input from messages
        List<?> messages = event.getMessages();
        if (messages != null && !messages.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Object msg : messages) {
                if (sb.length() > 0) sb.append("\n");
                if (msg instanceof com.embabel.chat.Message m) {
                    sb.append("[").append(m.getRole().name()).append("]: ").append(m.getContent());
                }
            }
            if (sb.length() > 0) {
                observation.highCardinalityKeyValue("input.value", truncate(sb.toString()));
            }
        }

        observation.start();
        Observation.Scope scope = observation.openScope();

        // Use interactionId as discriminant — unique per LLM call and shared with ToolLoopStartEvent.
        // This works even when events fire on different threads (CompletableFuture.supplyAsync).
        activeObservations.put(ObservationKeys.llmKey(runId, event.getInteraction().getId()), new ObservationContext(observation, scope));
        log.debug("Started LLM observation: llm:{} (runId: {}, action: {})", modelName, runId, actionName);
    }

    /**
     * Completes the LLM observation with response metadata.
     */
    private void onLlmResponse(LlmResponseEvent<?> event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        // Match by interactionId — same as stored in onLlmRequest
        var key = ObservationKeys.llmKey(runId, event.getRequest().getInteraction().getId());

        ObservationContext ctx = activeObservations.remove(key);

        if (ctx != null) {
            ctx.observation.highCardinalityKeyValue("embabel.llm.duration_ms",
                    String.valueOf(event.getRunningTime().toMillis()));
            Object response = event.getResponse();
            if (response != null) {
                ctx.observation.highCardinalityKeyValue("embabel.llm.output_type",
                        response.getClass().getSimpleName());
            }

            if (response instanceof Throwable error) {
                ctx.observation.error(error);
            } else if (response != null) {
                ctx.observation.highCardinalityKeyValue("output.value", truncate(response.toString()));
            }

            ctx.scope.close();
            ctx.observation.stop();

            log.debug("Completed LLM observation (runId: {}, key: {})", runId, key);
        }
    }

    // --- RAG Events ---

    /**
     * Creates an instant observation for a RAG event.
     * Determines observation name and attributes based on the specific RAG event subtype.
     */
    private void onRagEvent(AgentProcessRagEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        RagEvent ragEvent = event.getRagEvent();

        // Determine span name based on RAG event type
        String spanName;
        if (ragEvent instanceof RagRequestReceivedEvent) {
            spanName = "rag:request";
        } else if (ragEvent instanceof RagResponseEvent) {
            spanName = "rag:response";
        } else if (ragEvent instanceof EnhancementStartingRagPipelineEvent e) {
            spanName = "rag:enhancement:" + e.getEnhancerName();
        } else if (ragEvent instanceof EnhancementCompletedRagPipelineEvent e) {
            spanName = "rag:enhancement:" + e.getEnhancerName() + ":done";
        } else if (ragEvent instanceof InitialRequestRagPipelineEvent) {
            spanName = "rag:pipeline:request";
        } else if (ragEvent instanceof InitialResponseRagPipelineEvent) {
            spanName = "rag:pipeline:response";
        } else {
            spanName = "rag:event";
        }

        EmbabelObservationContext context = EmbabelObservationContext.rag(runId, spanName);

        Observation observation = Observation.createNotStarted(spanName, () -> context, observationRegistry);

        observation.lowCardinalityKeyValue("embabel.event.type", "rag");
        observation.lowCardinalityKeyValue("gen_ai.operation.name", "rag");
        observation.highCardinalityKeyValue("embabel.rag.query", ragEvent.getRequest().getQuery());

        // Add type-specific attributes
        if (ragEvent instanceof RagRequestReceivedEvent) {
            observation.highCardinalityKeyValue("input.value", truncate(ragEvent.getRequest().getQuery()));
        } else if (ragEvent instanceof RagResponseEvent re) {
            observation.highCardinalityKeyValue("embabel.rag.result_count",
                    String.valueOf(re.getRagResponse().getResults().size()));
            observation.highCardinalityKeyValue("output.value",
                    truncate(re.getRagResponse().getResults().toString()));
        } else if (ragEvent instanceof EnhancementStartingRagPipelineEvent e) {
            observation.highCardinalityKeyValue("embabel.rag.enhancer", e.getEnhancerName());
        } else if (ragEvent instanceof EnhancementCompletedRagPipelineEvent e) {
            observation.highCardinalityKeyValue("embabel.rag.enhancer", e.getEnhancerName());
        } else if (ragEvent instanceof RagPipelineEvent pe) {
            observation.highCardinalityKeyValue("embabel.rag.description", pe.getDescription());
        }

        // Instant observation
        observation.start();
        observation.stop();

        log.debug("Recorded RAG event: {} (runId: {})", spanName, runId);
    }

    // --- Ranking Events ---

    /** Records an instant observation for a ranking request event. */
    private void onRankingRequest(RankingChoiceRequestEvent<?> event) {
        EmbabelObservationContext context = EmbabelObservationContext.ranking("ranking:request");

        Observation observation = Observation.createNotStarted("ranking:request", () -> context, observationRegistry);

        observation.lowCardinalityKeyValue("embabel.event.type", "ranking");
        observation.lowCardinalityKeyValue("gen_ai.operation.name", "ranking");
        observation.highCardinalityKeyValue("embabel.ranking.type", event.getType().getSimpleName());
        observation.highCardinalityKeyValue("embabel.ranking.choices_count", String.valueOf(event.getChoices().size()));
        observation.highCardinalityKeyValue("input.value", truncate(event.getBasis().toString()));

        observation.start();
        observation.stop();

        log.debug("Recorded ranking request event");
    }

    /** Records an instant observation for a ranking choice made event. */
    private void onRankingChoiceMade(RankingChoiceMadeEvent<?> event) {
        Ranking<?> choice = event.getChoice();
        String chosenName = choice.getMatch().getName();

        EmbabelObservationContext context = EmbabelObservationContext.ranking("ranking:choice_made");

        Observation observation = Observation.createNotStarted("ranking:choice_made", () -> context, observationRegistry);

        observation.lowCardinalityKeyValue("embabel.event.type", "ranking");
        observation.lowCardinalityKeyValue("gen_ai.operation.name", "ranking");
        observation.highCardinalityKeyValue("embabel.ranking.chosen", chosenName);
        observation.highCardinalityKeyValue("embabel.ranking.score", String.valueOf(choice.getScore()));
        observation.highCardinalityKeyValue("embabel.ranking.type", event.getType().getSimpleName());
        observation.highCardinalityKeyValue("embabel.ranking.choices_count", String.valueOf(event.getChoices().size()));
        observation.highCardinalityKeyValue("input.value", truncate(event.getBasis().toString()));
        observation.highCardinalityKeyValue("output.value", truncate(event.getRankings().infoString(true, 0)));

        observation.start();
        observation.stop();

        log.debug("Recorded ranking choice made: {}", chosenName);
    }

    /** Records an instant observation with error for ranking choice that could not be made. */
    private void onRankingChoiceCouldNotBeMade(RankingChoiceCouldNotBeMadeEvent<?> event) {
        EmbabelObservationContext context = EmbabelObservationContext.ranking("ranking:no_choice");

        Observation observation = Observation.createNotStarted("ranking:no_choice", () -> context, observationRegistry);

        observation.lowCardinalityKeyValue("embabel.event.type", "ranking");
        observation.lowCardinalityKeyValue("gen_ai.operation.name", "ranking");
        observation.highCardinalityKeyValue("embabel.ranking.type", event.getType().getSimpleName());
        observation.highCardinalityKeyValue("embabel.ranking.confidence_cutoff", String.valueOf(event.getConfidenceCutOff()));
        observation.highCardinalityKeyValue("embabel.ranking.choices_count", String.valueOf(event.getChoices().size()));
        observation.highCardinalityKeyValue("input.value", truncate(event.getBasis().toString()));
        observation.highCardinalityKeyValue("output.value", truncate(event.getRankings().infoString(true, 0)));

        observation.start();
        observation.error(new RuntimeException("No ranking choice could be made"));
        observation.stop();

        log.debug("Recorded ranking choice could not be made");
    }

    // --- Dynamic Agent Creation ---

    /** Records an instant observation for dynamic agent creation. */
    private void onDynamicAgentCreation(DynamicAgentCreationEvent event) {
        String agentName = event.getAgent().getName();

        EmbabelObservationContext context = EmbabelObservationContext.dynamicAgentCreation("dynamic_agent:" + agentName);

        Observation observation = Observation.createNotStarted("dynamic_agent:" + agentName, () -> context, observationRegistry);

        observation.lowCardinalityKeyValue("embabel.event.type", "dynamic_agent_creation");
        observation.lowCardinalityKeyValue("gen_ai.operation.name", "create_agent");
        observation.highCardinalityKeyValue("embabel.agent.name", agentName);
        observation.highCardinalityKeyValue("input.value", truncate(event.getBasis().toString()));

        observation.start();
        observation.stop();

        log.debug("Recorded dynamic agent creation: {}", agentName);
    }

    // --- Process Killed ---

    /** Closes the agent observation with error status when the process is killed. */
    private void onProcessKilled(ProcessKilledEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        var key = ObservationKeys.agentKey(runId);

        ObservationContext ctx = activeObservations.remove(key);
        inputSnapshots.remove(key);
        planIterations.remove(runId);

        if (ctx != null) {
            ctx.observation.lowCardinalityKeyValue("embabel.agent.status", "killed");
            ctx.observation.error(new RuntimeException("Agent process was killed"));
            ctx.scope.close();
            ctx.observation.stop();

            log.debug("Recorded process killed for agent runId: {}", runId);
        }
    }

    // --- Utility Methods ---

    /** Finds best parent observation - prefers action over agent. */
    private ObservationContext findParentObservation(String runId) {
        var actionPrefix = ObservationKeys.ACTION_PREFIX + runId;
        for (String key : activeObservations.keySet()) {
            if (key.startsWith(actionPrefix)) {
                return activeObservations.get(key);
            }
        }
        return activeObservations.get(ObservationKeys.agentKey(runId));
    }

    private String truncate(String value) {
        return ObservationUtils.truncate(value, properties.getMaxAttributeLength());
    }
}
