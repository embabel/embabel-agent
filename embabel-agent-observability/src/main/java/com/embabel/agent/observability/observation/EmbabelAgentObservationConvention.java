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
 * <p><strong>STUCK is not an error.</strong> Status is recorded only as a tag. The span is
 * marked errored solely when the wrapped work throws (the {@code observe{}} at the call site
 * propagates the exception). A turn that ends STUCK — the expected resting state for a
 * ChatBot awaiting the next user message — returns normally, so its span closes OK.
 */
public class EmbabelAgentObservationConvention
        implements GlobalObservationConvention<AgentObservationContext> {

    private final int maxAttributeLength;

    public EmbabelAgentObservationConvention(int maxAttributeLength) {
        this.maxAttributeLength = maxAttributeLength;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof AgentObservationContext;
    }

    @Override
    public String getName() {
        return "embabel.agent";
    }

    @Override
    public String getContextualName(AgentObservationContext context) {
        return context.getProcess().getAgent().getName();
    }

    @Override
    public KeyValues getLowCardinalityKeyValues(AgentObservationContext context) {
        AgentProcess process = context.getProcess();
        return KeyValues.of(
                "gen_ai.operation.name", "agent",
                "embabel.agent.name", process.getAgent().getName(),
                "embabel.agent.is_subagent", String.valueOf(process.getParentId() != null),
                "embabel.agent.planner_type", process.getProcessOptions().getPlannerType().name(),
                "embabel.agent.status", process.getStatus().name());
    }

    @Override
    public KeyValues getHighCardinalityKeyValues(AgentObservationContext context) {
        AgentProcess process = context.getProcess();
        KeyValues kv = KeyValues.of(
                "embabel.run.id", process.getId(),
                "gen_ai.conversation.id", conversationId(process));
        if (process.getParentId() != null) {
            kv = kv.and("embabel.parent.id", process.getParentId());
        }
        String userId = userId(process);
        if (userId != null) {
            kv = kv.and("user.id", userId);
        }
        if (process.getGoal() != null) {
            kv = kv.and("embabel.goal", process.getGoal().getName());
        }
        String input = ObservationUtils.agentInput(process);
        if (!input.isEmpty()) {
            kv = kv.and("input.value", ObservationUtils.truncate(input, maxAttributeLength));
        }
        Object result = process.lastResult();
        if (result != null) {
            String output = ObservationUtils.truncate(result.toString(), maxAttributeLength);
            kv = kv.and("embabel.agent.result", output).and("output.value", output);
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

    /** User id from the last {@link User} bound on the blackboard, for per-user grouping; null if none. */
    private static String userId(AgentProcess process) {
        List<User> users = process.objectsOfType(User.class);
        if (!users.isEmpty()) {
            return users.get(users.size() - 1).getId();
        }
        return null;
    }
}
