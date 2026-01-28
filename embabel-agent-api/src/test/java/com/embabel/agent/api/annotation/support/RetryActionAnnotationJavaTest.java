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
package com.embabel.agent.api.annotation.support;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.core.ProcessOptions;
import com.embabel.agent.core.ActionRetryPolicy;
import com.embabel.agent.test.integration.IntegrationTestUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java tests for @Retry annotation.
 */
class ActionRetryPolicyAnnotationJavaTest {

    @Test
    void retryMethodFailsOnlyOnceSucceedsSecond() {
        var reader = new AgentMetadataReader();
        var instance = new JavaAgentWithTwoRetryActions();
        var metadata = reader.createAgentMetadata(instance);

        assertNotNull(metadata);
        var agent = (com.embabel.agent.core.Agent) metadata;

        var ap = IntegrationTestUtils.dummyAgentPlatform();
        var agentProcess = ap.createAgentProcess(
                agent,
                ProcessOptions.DEFAULT.withPlannerType(PlannerType.UTILITY),
                Map.of(
                        "input", new JavaRetryTestInput("test")
                )
        );


        Assertions.assertDoesNotThrow(() -> agentProcess.run().resultOfType(JavaRetryTestOutput.class));

        assertEquals(2, instance.retryInvocations.get(), "Retryable method should have been invoked");
    }

    @Test
    void retryMethodFailsOnlyOnce() {
        var reader = new AgentMetadataReader();
        var instance = new JavaAgentWithRetryMethod();
        var metadata = reader.createAgentMetadata(instance);

        assertNotNull(metadata);
        var agent = (com.embabel.agent.core.Agent) metadata;

        var ap = IntegrationTestUtils.dummyAgentPlatform();
        var agentProcess = ap.createAgentProcess(
                agent,
                ProcessOptions.DEFAULT.withPlannerType(PlannerType.UTILITY),
                Map.of(
                        "input", new JavaRetryTestInput("test")
                )
        );


        Assertions.assertThrows(RuntimeException.class, () -> agentProcess.run().resultOfType(JavaRetryTestOutput.class));

        assertEquals(1, instance.retryInvocationCount.get(), "Retryable method should have been invoked");
    }

}

/**
 * Simple domain class for testing.
 */
record JavaRetryTestInput(String value) {
}

/**
 * Simple output class for testing.
 */
record JavaRetryTestOutput(String result) {
}

/**
 * Agent with @Cost method that uses nullable domain parameter.
 */
@Agent(description = "Java agent with 1 retry", planner = PlannerType.UTILITY)
class JavaAgentWithRetryMethod {

    final AtomicInteger retryInvocationCount = new AtomicInteger(0);

    @Action(actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE)
    public JavaRetryTestOutput perform(JavaRetryTestInput input) {
        retryInvocationCount.incrementAndGet();
        throw new RuntimeException("Failed on purpose!");
    }
}

/**
 * Agent with two actions with different dynamic costs.
 */
@Agent(description = "Java agent with two dynamic cost actions", planner = PlannerType.GOAP)
class JavaAgentWithTwoRetryActions {

    final AtomicInteger retryInvocations = new AtomicInteger(0);

    @AchievesGoal(description = "Process the input")
    @Action(actionRetryPolicyExpression = "${retry-twice}")
    public JavaRetryTestOutput firstAction(JavaRetryTestInput input) {
        retryInvocations.incrementAndGet();
        if (retryInvocations.get() == 1)
            throw new RuntimeException("Failed!");

        return new JavaRetryTestOutput("Success!");

    }


}
