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

import io.micrometer.observation.Observation;

/**
 * Custom observation context for Embabel agent events.
 * Carries metadata for tracing hierarchy (runId, parentRunId, isRoot).
 *
 * @author Quantpulsar 2025-2026
 */
public class EmbabelObservationContext extends Observation.Context {

    /**
     * Types of events that can be observed during agent execution.
     */
    public enum EventType {
        /** Main agent processing event. */
        AGENT_PROCESS,
        /** Action execution event. */
        ACTION,
        /** Goal achievement event. */
        GOAL,
        /** External tool invocation event. */
        TOOL_CALL,
        /** Planning phase event. */
        PLANNING,
        /** State transition event. */
        STATE_TRANSITION,
        /** Agent lifecycle event. */
        LIFECYCLE,
        /** LLM call event. */
        LLM_CALL,
        /** Tool loop execution event. */
        TOOL_LOOP,
        /** RAG event. */
        RAG,
        /** Ranking/selection event. */
        RANKING,
        /** Dynamic agent creation event. */
        DYNAMIC_AGENT_CREATION,
        /** Custom user-defined tracked operation. */
        CUSTOM
    }

    private final boolean root;
    private final String runId;
    private final EventType eventType;
    private final String parentRunId;

    /**
     * Creates a new observation context.
     *
     * @param root        true if this is a root agent
     * @param runId       unique run identifier
     * @param name        observation name
     * @param eventType   type of event
     * @param parentRunId parent run identifier, or null
     */
    public EmbabelObservationContext(boolean root, String runId, String name,
                                     EventType eventType, String parentRunId) {
        this.root = root;
        this.runId = runId;
        this.eventType = eventType;
        this.parentRunId = parentRunId;
        setName(name);
    }

    /**
     * Creates context for a root agent (starts new trace).
     *
     * @param runId     unique run identifier
     * @param agentName name of the agent
     * @return the observation context
     */
    public static EmbabelObservationContext rootAgent(String runId, String agentName) {
        return new EmbabelObservationContext(true, runId, agentName, EventType.AGENT_PROCESS, null);
    }

    /**
     * Creates context for a subagent (child of parent agent).
     *
     * @param runId       unique run identifier
     * @param agentName   name of the agent
     * @param parentRunId parent run identifier
     * @return the observation context
     */
    public static EmbabelObservationContext subAgent(String runId, String agentName, String parentRunId) {
        return new EmbabelObservationContext(false, runId, agentName, EventType.AGENT_PROCESS, parentRunId);
    }

    /**
     * Creates context for an action.
     *
     * @param runId      unique run identifier
     * @param actionName name of the action
     * @return the observation context
     */
    public static EmbabelObservationContext action(String runId, String actionName) {
        return new EmbabelObservationContext(false, runId, actionName, EventType.ACTION, null);
    }

    /**
     * Creates context for a goal achievement.
     *
     * @param runId    unique run identifier
     * @param goalName name of the goal
     * @return the observation context
     */
    public static EmbabelObservationContext goal(String runId, String goalName) {
        return new EmbabelObservationContext(false, runId, goalName, EventType.GOAL, null);
    }

    /**
     * Creates context for a tool call.
     *
     * @param runId    unique run identifier
     * @param toolName name of the tool
     * @return the observation context
     */
    public static EmbabelObservationContext toolCall(String runId, String toolName) {
        return new EmbabelObservationContext(false, runId, toolName, EventType.TOOL_CALL, null);
    }

    /**
     * Creates context for a planning event.
     *
     * @param runId        unique run identifier
     * @param planningName name of the planning phase
     * @return the observation context
     */
    public static EmbabelObservationContext planning(String runId, String planningName) {
        return new EmbabelObservationContext(false, runId, planningName, EventType.PLANNING, null);
    }

    /**
     * Creates context for a state transition.
     *
     * @param runId     unique run identifier
     * @param stateName name of the state
     * @return the observation context
     */
    public static EmbabelObservationContext stateTransition(String runId, String stateName) {
        return new EmbabelObservationContext(false, runId, stateName, EventType.STATE_TRANSITION, null);
    }

    /**
     * Creates context for a lifecycle state event.
     *
     * @param runId          unique run identifier
     * @param lifecycleState name of the lifecycle state
     * @return the observation context
     */
    public static EmbabelObservationContext lifecycle(String runId, String lifecycleState) {
        return new EmbabelObservationContext(false, runId, lifecycleState, EventType.LIFECYCLE, null);
    }

    /**
     * Creates context for an LLM call.
     *
     * @param runId   unique run identifier
     * @param llmName name of the LLM model
     * @return the observation context
     */
    public static EmbabelObservationContext llmCall(String runId, String llmName) {
        return new EmbabelObservationContext(false, runId, llmName, EventType.LLM_CALL, null);
    }

    /**
     * Creates context for a tool loop execution.
     *
     * @param runId        unique run identifier
     * @param toolLoopName name of the tool loop
     * @return the observation context
     */
    public static EmbabelObservationContext toolLoop(String runId, String toolLoopName) {
        return new EmbabelObservationContext(false, runId, toolLoopName, EventType.TOOL_LOOP, null);
    }

    /**
     * Creates context for a ranking event.
     *
     * @param rankingName name of the ranking event
     * @return the observation context
     */
    public static EmbabelObservationContext ranking(String rankingName) {
        return new EmbabelObservationContext(false, "", rankingName, EventType.RANKING, null);
    }

    /**
     * Creates context for a dynamic agent creation event.
     *
     * @param agentName name of the dynamically created agent
     * @return the observation context
     */
    public static EmbabelObservationContext dynamicAgentCreation(String agentName) {
        return new EmbabelObservationContext(false, "", agentName, EventType.DYNAMIC_AGENT_CREATION, null);
    }

    /**
     * Creates context for a RAG event.
     *
     * @param runId   unique run identifier
     * @param ragName name of the RAG event
     * @return the observation context
     */
    public static EmbabelObservationContext rag(String runId, String ragName) {
        return new EmbabelObservationContext(false, runId, ragName, EventType.RAG, null);
    }

    /**
     * Creates context for a custom tracked operation.
     *
     * @param runId unique run identifier, or empty if outside an agent process
     * @param name  name of the tracked operation
     * @return the observation context
     */
    public static EmbabelObservationContext custom(String runId, String name) {
        return new EmbabelObservationContext(false, runId, name, EventType.CUSTOM, null);
    }

    /**
     * Returns whether this is a root agent context.
     *
     * @return true if this is a root agent context
     */
    public boolean isRoot() {
        return root;
    }

    /**
     * Returns the unique run identifier.
     *
     * @return the unique run identifier
     */
    public String getRunId() {
        return runId;
    }

    /**
     * Returns the event type.
     *
     * @return the event type
     */
    public EventType getEventType() {
        return eventType;
    }

    /**
     * Returns the parent run identifier.
     *
     * @return the parent run identifier, or null
     */
    public String getParentRunId() {
        return parentRunId;
    }
}
