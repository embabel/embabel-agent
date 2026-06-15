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

import com.embabel.agent.api.event.observation.AgentObservationContext;
import com.embabel.agent.api.identity.User;
import com.embabel.agent.core.AgentProcess;
import com.embabel.chat.Conversation;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.GlobalObservationConvention;
import io.micrometer.observation.Observation;

import java.util.List;

/**
 * Global convention for the {@code embabel.agent} span (one agent turn).
 *
 * <p>Reads everything from the live {@link AgentProcess} wrapped by the thin
 * {@link AgentObservationContext}; the core carries no extraction logic. Low- and
 * high-cardinality values are read at <em>stop</em> time, so {@code status} reflects the
 * final outcome.
 *
 * <p><strong>Status is a tag, not a span error.</strong> The span is marked errored solely when
 * the wrapped work throws (the {@code observe{}} at the call site propagates the exception). Every
 * terminal status reached <em>without</em> an exception — COMPLETED, STUCK, WAITING, FAILED, KILLED,
 * TERMINATED — therefore closes the span OK, distinguished only by the {@code embabel.agent.status}
 * tag. STUCK in particular is the expected resting state of a ChatBot awaiting the next user message,
 * so it must not look like a failure.
 */
public class EmbabelAgentObservationConvention
        implements GlobalObservationConvention<AgentObservationContext> {

    private final int maxAttributeLength;
    private final boolean captureMessageContent;

    public EmbabelAgentObservationConvention(int maxAttributeLength) {
        this(maxAttributeLength, true);
    }

    /**
     * @param maxAttributeLength    truncation bound for message bodies
     * @param captureMessageContent when {@code false}, omit the {@code input.value} /
     *                              {@code output.value} / {@code embabel.agent.result} bodies
     *                              (may contain PII); metadata is still recorded
     */
    public EmbabelAgentObservationConvention(int maxAttributeLength, boolean captureMessageContent) {
        this.maxAttributeLength = maxAttributeLength;
        this.captureMessageContent = captureMessageContent;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof AgentObservationContext;
    }

    @Override
    public String getName() {
        return SpanAttributes.EMBABEL_AGENT;
    }

    @Override
    public String getContextualName(AgentObservationContext context) {
        // Prefix the type so the span is identifiable regardless of how the user named the agent
        // (e.g. "researcher" -> "agent researcher"), consistent with the <type> <name> scheme.
        return "agent " + context.getProcess().getAgent().getName();
    }

    @Override
    public KeyValues getLowCardinalityKeyValues(AgentObservationContext context) {
        AgentProcess process = context.getProcess();
        return KeyValues.of(
                SpanAttributes.GEN_AI_OPERATION_NAME, "agent",
                SpanAttributes.EMBABEL_EVENT_TYPE, "agent_process",
                SpanAttributes.EMBABEL_AGENT_NAME, process.getAgent().getName(),
                SpanAttributes.EMBABEL_AGENT_IS_SUBAGENT, String.valueOf(process.getParentId() != null),
                SpanAttributes.EMBABEL_AGENT_PLANNER_TYPE, process.getProcessOptions().getPlannerType().name(),
                SpanAttributes.EMBABEL_AGENT_STATUS, process.getStatus().name());
    }

    @Override
    public KeyValues getHighCardinalityKeyValues(AgentObservationContext context) {
        AgentProcess process = context.getProcess();
        KeyValues kv = KeyValues.of(
                SpanAttributes.EMBABEL_RUN_ID, process.getId(),
                SpanAttributes.GEN_AI_CONVERSATION_ID, conversationId(process));
        if (process.getParentId() != null) {
            kv = kv.and(SpanAttributes.EMBABEL_PARENT_ID, process.getParentId());
        }
        String userId = userId(process);
        if (userId != null) {
            kv = kv.and(SpanAttributes.USER_ID, userId);
        }
        if (process.getGoal() != null) {
            kv = kv.and(SpanAttributes.EMBABEL_GOAL, process.getGoal().getName());
        }
        if (captureMessageContent) {
            String input = ObservationUtils.agentInput(process);
            if (!input.isEmpty()) {
                kv = kv.and(SpanAttributes.INPUT_VALUE, ObservationUtils.truncate(input, maxAttributeLength));
            }
            Object result = process.lastResult();
            if (result != null) {
                String output = ObservationUtils.truncate(result.toString(), maxAttributeLength);
                kv = kv.and(SpanAttributes.EMBABEL_AGENT_RESULT, output).and(SpanAttributes.OUTPUT_VALUE, output);
            }
        }
        return kv;
    }

    /**
     * Session id for backends such as Langfuse: the stable id of the last {@link Conversation}
     * on the blackboard, falling back to the run id. Using the conversation id (not the run id)
     * groups every turn of a conversation — including processes spawned after a restart — into a
     * single session.
     */
    private static String conversationId(AgentProcess process) {
        List<Conversation> conversations = process.objectsOfType(Conversation.class);
        if (!conversations.isEmpty()) {
            return conversations.get(conversations.size() - 1).getId();
        }
        return process.getId();
    }

    /**
     * User id for per-user grouping (e.g. Langfuse). Prefers the canonical process identity
     * {@code ProcessOptions.identities.forUser} — the same source {@code OperationContext.user()}
     * reads, and where the chatbot binds the user — so chat turns carry a {@code user.id}, not just a
     * conversation id. Falls back to the last {@link User} bound on the blackboard (for agents that
     * place a User as a domain object). Null if neither is present.
     */
    private static String userId(AgentProcess process) {
        User forUser = process.getProcessOptions().getIdentities().getForUser();
        if (forUser != null) {
            return forUser.getId();
        }
        List<User> users = process.objectsOfType(User.class);
        if (!users.isEmpty()) {
            return users.get(users.size() - 1).getId();
        }
        return null;
    }
}
