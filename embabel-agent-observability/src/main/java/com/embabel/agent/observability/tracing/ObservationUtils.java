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

import com.embabel.agent.core.Action;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.Blackboard;
import com.embabel.agent.core.IoBinding;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.chat.Message;
import com.embabel.plan.Plan;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Shared pure utility methods for observation listeners.
 */
final class ObservationUtils {

    private ObservationUtils() {}

    static String truncate(String value, int maxLength) {
        if (value == null) return "";
        return value.length() > maxLength ? value.substring(0, maxLength) + "..." : value;
    }

    /** Local name from a possibly fully-qualified name: the segment after the last dot, else the name itself. */
    static String shortName(String name) {
        if (name == null) return "";
        int lastDot = name.lastIndexOf('.');
        return lastDot >= 0 && lastDot < name.length() - 1 ? name.substring(lastDot + 1) : name;
    }

    /**
     * Chat messages formatted as span input: one {@code [role]: content} line per message. The role
     * is spelled lowercase, matching the OpenTelemetry GenAI role convention (and the {@code input.value}
     * bridge in {@link ChatModelObservationFilter}) so the same role is never spelled two ways.
     */
    static String formatMessages(List<? extends Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            if (sb.length() > 0) sb.append("\n");
            String role = m.getRole() == null ? "null" : m.getRole().name().toLowerCase(Locale.ROOT);
            sb.append("[").append(role).append("]: ").append(m.getContent());
        }
        return sb.toString();
    }

    /** The agent turn's input: the content of the {@link UserInput}(s) bound on the blackboard. */
    static String agentInput(AgentProcess process) {
        List<UserInput> inputs = process.objectsOfType(UserInput.class);
        if (inputs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (UserInput input : inputs) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(input.getContent());
        }
        return sb.toString();
    }

    static String getActionInputs(Action action, AgentProcess process) {
        return resolveBindings(action.getInputs(), process);
    }

    /**
     * The action's declared outputs resolved from the blackboard: its actual product, not the global
     * {@code lastResult()}. A default ({@code "it"}) output binding resolves to the last result of the
     * declared type, so this captures the value the action just produced.
     */
    static String getActionOutputs(Action action, AgentProcess process) {
        return resolveBindings(action.getOutputs(), process);
    }

    /** The plan as numbered steps, one action name per line, with the target goal appended. */
    static String formatPlanSteps(Plan plan) {
        if (plan == null || plan.getActions() == null || plan.getActions().isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (var action : plan.getActions()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(index++).append(". ").append(action.getName());
        }
        if (plan.getGoal() != null) {
            sb.append("\n-> Goal: ").append(plan.getGoal().getName());
        }
        return sb.toString();
    }

    /** Resolve a set of {@code name:Type} bindings against the blackboard, one {@code name (Type): value} line each. */
    private static String resolveBindings(Set<IoBinding> bindings, AgentProcess process) {
        if (bindings == null || bindings.isEmpty()) {
            return "";
        }
        Blackboard blackboard = process.getBlackboard();
        StringBuilder sb = new StringBuilder();
        for (IoBinding binding : bindings) {
            String bindingValue = binding.getValue();
            String name;
            String type;
            if (bindingValue.contains(":")) {
                String[] parts = bindingValue.split(":", 2);
                name = parts[0];
                type = parts[1];
            } else {
                name = "it"; // DEFAULT_BINDING
                type = bindingValue;
            }
            Object value = blackboard.getValue(name, type, process.getAgent());
            if (value != null) {
                if (sb.length() > 0) sb.append("\n---\n");
                sb.append(name).append(" (").append(type).append("): ");
                sb.append(value.toString());
            }
        }
        return sb.toString();
    }

}
