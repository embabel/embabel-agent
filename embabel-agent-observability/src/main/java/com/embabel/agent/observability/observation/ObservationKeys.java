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
 * Constants and helpers for observation map keys and span names.
 */
final class ObservationKeys {

    static final String AGENT_PREFIX = "agent:";
    static final String ACTION_PREFIX = "action:";
    static final String LLM_PREFIX = "llm:";
    static final String TOOL_LOOP_PREFIX = "tool-loop:";
    static final String TOOL_PREFIX = "tool:";

    private ObservationKeys() {}

    // Map keys (prefix + runId or prefix + runId + ":" + subId)
    static String agentKey(String runId) { return AGENT_PREFIX + runId; }
    static String actionKey(String runId, String actionName) { return ACTION_PREFIX + runId + ":" + actionName; }
    static String llmKey(String runId, String interactionId) { return LLM_PREFIX + runId + ":" + interactionId; }
    static String toolLoopKey(String runId, String interactionId) { return TOOL_LOOP_PREFIX + runId + ":" + interactionId; }
    static String toolKey(String runId, String toolName) { return TOOL_PREFIX + runId + ":" + toolName; }

    // Span names (prefix + name, no runId)
    static String toolSpanName(String toolName) { return TOOL_PREFIX + toolName; }
    static String toolLoopSpanName(String interactionId) { return TOOL_LOOP_PREFIX + interactionId; }
}
