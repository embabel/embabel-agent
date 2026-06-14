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
package com.embabel.agent.observability;

import org.jetbrains.annotations.ApiStatus;

/**
 * Single source of truth for the span attribute keys and observation/span names emitted by the
 * Embabel observability conventions, filters and listeners. Centralising these string constants
 * avoids the duplication that crept across the convention classes.
 *
 * <p>Values are wire-format identifiers: {@code gen_ai.*} follow the OpenTelemetry GenAI semantic
 * conventions, {@code input.value}/{@code output.value} bridge OpenInference, and {@code embabel.*}
 * are Embabel-specific. They must not change without coordinating with downstream consumers.
 */
@ApiStatus.Internal
public final class SpanAttributes {

    // OpenTelemetry GenAI semantic conventions
    public static final String GEN_AI_CONVERSATION_ID = "gen_ai.conversation.id";
    public static final String GEN_AI_INPUT_MESSAGES = "gen_ai.input.messages";
    public static final String GEN_AI_OPERATION_NAME = "gen_ai.operation.name";
    public static final String GEN_AI_OUTPUT_MESSAGES = "gen_ai.output.messages";
    public static final String GEN_AI_REQUEST_MAX_TOKENS = "gen_ai.request.max_tokens";
    public static final String GEN_AI_REQUEST_MODEL = "gen_ai.request.model";
    public static final String GEN_AI_REQUEST_TEMPERATURE = "gen_ai.request.temperature";
    public static final String GEN_AI_RESPONSE_MODEL = "gen_ai.response.model";
    public static final String GEN_AI_TOOL_CALL_ARGUMENTS = "gen_ai.tool.call.arguments";
    public static final String GEN_AI_TOOL_CALL_RESULT = "gen_ai.tool.call.result";
    public static final String GEN_AI_TOOL_NAME = "gen_ai.tool.name";
    public static final String GEN_AI_USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens";
    public static final String GEN_AI_USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens";
    public static final String GEN_AI_USAGE_TOTAL_TOKENS = "gen_ai.usage.total_tokens";

    // OpenInference bridge
    public static final String INPUT_VALUE = "input.value";
    public static final String OUTPUT_VALUE = "output.value";

    // Embabel-specific span names and attribute keys
    public static final String EMBABEL_ACTION = "embabel.action";
    public static final String EMBABEL_ACTION_NAME = "embabel.action.name";
    public static final String EMBABEL_ACTION_RESULT = "embabel.action.result";
    public static final String EMBABEL_ACTION_SHORT_NAME = "embabel.action.short_name";
    public static final String EMBABEL_ACTION_STATUS = "embabel.action.status";
    public static final String EMBABEL_AGENT = "embabel.agent";
    public static final String EMBABEL_AGENT_IS_SUBAGENT = "embabel.agent.is_subagent";
    public static final String EMBABEL_AGENT_NAME = "embabel.agent.name";
    public static final String EMBABEL_AGENT_PLANNER_TYPE = "embabel.agent.planner_type";
    public static final String EMBABEL_AGENT_RESULT = "embabel.agent.result";
    public static final String EMBABEL_AGENT_STATUS = "embabel.agent.status";
    public static final String EMBABEL_DYNAMIC_AGENT_BASIS = "embabel.dynamic_agent.basis";
    public static final String EMBABEL_DYNAMIC_AGENT_CREATION = "embabel.dynamic_agent_creation";
    public static final String EMBABEL_EMBEDDING = "embabel.embedding";
    /**
     * Embabel-native event type for the span, one value per kind of observed operation
     * (e.g. {@code agent_process}, {@code action}, {@code tool_call}, {@code llm_call}, ...).
     * Unlike {@code gen_ai.operation.name} — which is present only on GenAI-classified spans and
     * absent on the structural LLM wrapper / cost-record spans — this tag is emitted on <em>every</em>
     * Embabel span, giving downstream exporters (e.g. Langfuse) an unambiguous, complete classifier.
     */
    public static final String EMBABEL_EVENT_TYPE = "embabel.event.type";
    public static final String EMBABEL_GOAL = "embabel.goal";
    public static final String EMBABEL_GOAL_NAME = "embabel.goal.name";
    public static final String EMBABEL_GOAL_RESULT = "embabel.goal.result";
    public static final String EMBABEL_GOAL_SHORT_NAME = "embabel.goal.short_name";
    public static final String EMBABEL_INTERACTION_ID = "embabel.interaction.id";
    public static final String EMBABEL_LIFECYCLE = "embabel.lifecycle";
    public static final String EMBABEL_LIFECYCLE_STATE = "embabel.lifecycle.state";
    public static final String EMBABEL_LLM = "embabel.llm";
    public static final String EMBABEL_LLM_COST = "embabel.llm.cost";
    public static final String EMBABEL_LLM_INVOCATION = "embabel.llm.invocation";
    public static final String EMBABEL_LLM_MODEL = "embabel.llm.model";
    public static final String EMBABEL_PARENT_ID = "embabel.parent.id";
    public static final String EMBABEL_PLAN_ACTION_COUNT = "embabel.plan.action_count";
    public static final String EMBABEL_PLAN_GOAL = "embabel.plan.goal";
    public static final String EMBABEL_PLAN_GOAL_SHORT = "embabel.plan.goal_short";
    public static final String EMBABEL_PLAN_IS_REPLANNING = "embabel.plan.is_replanning";
    public static final String EMBABEL_PLAN_ITERATION = "embabel.plan.iteration";
    public static final String EMBABEL_PLANNING = "embabel.planning";
    public static final String EMBABEL_RAG = "embabel.rag";
    public static final String EMBABEL_RAG_QUERY = "embabel.rag.query";
    public static final String EMBABEL_RAG_RESULT_COUNT = "embabel.rag.result_count";
    public static final String EMBABEL_RAG_SERVICE = "embabel.rag.service";
    public static final String EMBABEL_RANKING = "embabel.ranking";
    public static final String EMBABEL_RANKING_CHOICE = "embabel.ranking.choice";
    public static final String EMBABEL_RANKING_CONFIDENCE_CUTOFF = "embabel.ranking.confidence_cutoff";
    public static final String EMBABEL_RANKING_OPTION_COUNT = "embabel.ranking.option_count";
    public static final String EMBABEL_RANKING_SCORE = "embabel.ranking.score";
    public static final String EMBABEL_RANKING_TYPE = "embabel.ranking.type";
    public static final String EMBABEL_REPLAN = "embabel.replan";
    public static final String EMBABEL_REPLAN_REASON = "embabel.replan.reason";
    public static final String EMBABEL_RUN_ID = "embabel.run.id";
    public static final String EMBABEL_STATE_FROM = "embabel.state.from";
    public static final String EMBABEL_STATE_TO = "embabel.state.to";
    public static final String EMBABEL_STATE_TRANSITION = "embabel.state_transition";
    public static final String EMBABEL_TOOL = "embabel.tool";
    public static final String EMBABEL_TOOL_CORRELATION_ID = "embabel.tool.correlation_id";
    public static final String EMBABEL_TOOL_DURATION_MS = "embabel.tool.duration_ms";
    public static final String EMBABEL_TOOL_ERROR_MESSAGE = "embabel.tool.error.message";
    public static final String EMBABEL_TOOL_ERROR_TYPE = "embabel.tool.error.type";
    public static final String EMBABEL_TOOL_GROUP_NAME = "embabel.tool.group.name";
    public static final String EMBABEL_TOOL_GROUP_ROLE = "embabel.tool.group.role";
    public static final String EMBABEL_TOOL_NAME = "embabel.tool.name";
    public static final String EMBABEL_TOOL_STATUS = "embabel.tool.status";
    public static final String EMBABEL_TOOL_LOOP = "embabel.tool_loop";
    public static final String EMBABEL_TOOL_LOOP_COMPLETED = "embabel.tool_loop.completed";
    public static final String EMBABEL_TOOL_LOOP_DURATION_MS = "embabel.tool_loop.duration_ms";
    public static final String EMBABEL_TOOL_LOOP_MAX_ITERATIONS = "embabel.tool_loop.max_iterations";
    public static final String EMBABEL_TOOL_LOOP_OUTPUT_CLASS = "embabel.tool_loop.output_class";
    public static final String EMBABEL_TOOL_LOOP_REPLAN_REQUESTED = "embabel.tool_loop.replan_requested";
    public static final String EMBABEL_TOOL_LOOP_TOOL_NAMES = "embabel.tool_loop.tool_names";
    public static final String EMBABEL_TOOL_LOOP_TOTAL_ITERATIONS = "embabel.tool_loop.total_iterations";
    public static final String EMBABEL_TRACKED_AGENT = "embabel.tracked.agent";
    public static final String EMBABEL_TRACKED_ARGS = "embabel.tracked.args";
    public static final String EMBABEL_TRACKED_CLASS = "embabel.tracked.class";
    public static final String EMBABEL_TRACKED_DESCRIPTION = "embabel.tracked.description";
    public static final String EMBABEL_TRACKED_RESULT = "embabel.tracked.result";
    public static final String EMBABEL_TRACKED_TYPE = "embabel.tracked.type";

    private SpanAttributes() {
    }
}
