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

/**
 * Constants and helpers for building the keys used to look up active observations
 * and the names given to the resulting spans.
 *
 * <p>Map keys uniquely identify an observation within a run and are built as
 * {@code prefix + runId} or {@code prefix + runId + ":" + subId}, where {@code subId}
 * is an action name, interaction id or tool name. Span names are human-readable
 * labels carrying no run id.
 */
final class ObservationKeys {

    static final String AGENT_PREFIX = "agent:";
    static final String ACTION_PREFIX = "action:";
    static final String LLM_PREFIX = "llm:";
    static final String TOOL_LOOP_PREFIX = "tool-loop:";
    static final String TOOL_PREFIX = "tool:";

    private ObservationKeys() {}

    // Map keys (prefix + runId or prefix + runId + ":" + subId)

    /**
     * @param runId the agent process run id
     * @return the map key identifying the agent observation for the run
     */
    static String agentKey(String runId) { return AGENT_PREFIX + runId; }

    /**
     * @param runId      the agent process run id
     * @param actionName the name of the action
     * @return the map key identifying the action observation within the run
     */
    static String actionKey(String runId, String actionName) { return ACTION_PREFIX + runId + ":" + actionName; }

    /**
     * @param runId         the agent process run id
     * @param interactionId the LLM interaction id, unique per call within the run
     * @return the map key identifying the LLM observation within the run
     */
    static String llmKey(String runId, String interactionId) { return LLM_PREFIX + runId + ":" + interactionId; }

    /**
     * @param runId         the agent process run id
     * @param interactionId the tool-loop interaction id, unique per loop within the run
     * @return the map key identifying the tool-loop observation within the run
     */
    static String toolLoopKey(String runId, String interactionId) { return TOOL_LOOP_PREFIX + runId + ":" + interactionId; }

    /**
     * @param runId    the agent process run id
     * @param toolName the name of the tool
     * @return the map key identifying the tool observation within the run
     */
    static String toolKey(String runId, String toolName) { return TOOL_PREFIX + runId + ":" + toolName; }

    // Span names (prefix + name, no runId)

    /**
     * @param toolName the name of the tool
     * @return the span name for a tool call
     */
    static String toolSpanName(String toolName) { return TOOL_PREFIX + toolName; }

    /**
     * Returns a stable, low-cardinality span name shared by all tool loops. No
     * interaction id is folded in: doing so would create a distinct name per
     * interaction and blow up cardinality.
     *
     * @return the constant span name {@link #TOOL_LOOP_PREFIX} for any tool loop
     */
    static String toolLoopSpanName() { return TOOL_LOOP_PREFIX; }
}
