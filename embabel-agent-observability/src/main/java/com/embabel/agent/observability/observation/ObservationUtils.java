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

import com.embabel.agent.api.event.ToolCallResponseEvent;
import com.embabel.agent.core.Action;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.Blackboard;
import com.embabel.agent.core.IoBinding;
import com.embabel.plan.Plan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared pure utility methods for observation listeners.
 */
final class ObservationUtils {

    private static final Logger log = LoggerFactory.getLogger(ObservationUtils.class);

    private ObservationUtils() {}

    static String truncate(String value, int maxLength) {
        if (value == null) return "";
        return value.length() > maxLength ? value.substring(0, maxLength) + "..." : value;
    }

    static String extractGoalName(AgentProcess process) {
        if (process.getGoal() != null) {
            return process.getGoal().getName();
        } else if (!process.getAgent().getGoals().isEmpty()) {
            return process.getAgent().getGoals().iterator().next().getName();
        }
        return "unknown";
    }

    static String getBlackboardSnapshot(AgentProcess process) {
        var objects = process.getBlackboard().getObjects();
        if (objects == null || objects.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object obj : objects) {
            if (obj != null) {
                if (sb.length() > 0) sb.append("\n---\n");
                sb.append(obj.getClass().getSimpleName()).append(": ");
                sb.append(obj.toString());
            }
        }
        return sb.toString();
    }

    static String getActionInputs(Action action, AgentProcess process) {
        var inputs = action.getInputs();
        if (inputs == null || inputs.isEmpty()) {
            return "";
        }
        Blackboard blackboard = process.getBlackboard();
        StringBuilder sb = new StringBuilder();
        for (IoBinding input : inputs) {
            String bindingValue = input.getValue();
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

    /**
     * Extracts the successful result from a Kotlin Result via reflection.
     * Kotlin mangles getResult as getResult-XXXXX for value classes.
     */
    static Object extractToolResult(ToolCallResponseEvent event) {
        try {
            java.lang.reflect.Method getResultMethod = null;
            for (java.lang.reflect.Method m : ToolCallResponseEvent.class.getMethods()) {
                if (m.getName().startsWith("getResult") && m.getParameterCount() == 0) {
                    getResultMethod = m;
                    break;
                }
            }
            if (getResultMethod == null) {
                log.trace("getResult method not found on ToolCallResponseEvent");
                return null;
            }
            Object result = getResultMethod.invoke(event);
            if (result == null) {
                return null;
            }
            try {
                java.lang.reflect.Method getOrNullMethod = result.getClass().getMethod("getOrNull");
                return getOrNullMethod.invoke(result);
            } catch (NoSuchMethodException e) {
                return result;
            }
        } catch (Exception e) {
            log.trace("Could not extract tool result: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Extracts the error from a Kotlin Result via reflection.
     */
    static Throwable extractToolError(ToolCallResponseEvent event) {
        try {
            java.lang.reflect.Method getResultMethod = null;
            for (java.lang.reflect.Method m : ToolCallResponseEvent.class.getMethods()) {
                if (m.getName().startsWith("getResult") && m.getParameterCount() == 0) {
                    getResultMethod = m;
                    break;
                }
            }
            if (getResultMethod == null) {
                log.trace("getResult method not found on ToolCallResponseEvent");
                return null;
            }
            Object result = getResultMethod.invoke(event);
            if (result == null) {
                return null;
            }
            if (result instanceof Throwable) {
                return (Throwable) result;
            }
            try {
                java.lang.reflect.Method exceptionOrNullMethod = result.getClass().getMethod("exceptionOrNull");
                Object error = exceptionOrNullMethod.invoke(result);
                if (error instanceof Throwable) {
                    return (Throwable) error;
                }
            } catch (NoSuchMethodException e) {
                return null;
            }
        } catch (Exception e) {
            log.trace("Could not extract tool error: {}", e.getMessage());
        }
        return null;
    }
}
