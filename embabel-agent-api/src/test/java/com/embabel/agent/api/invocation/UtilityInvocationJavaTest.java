/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.api.invocation;

import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.api.common.scope.AgentScopeBuilder;
import com.embabel.agent.core.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UtilityInvocationJavaTest {

    private AgentPlatform agentPlatform;
    private AgentScope agentScope;
    private AgentScopeBuilder agentScopeBuilder;
    private AgentProcess agentProcess;
    private Agent agent;

    @BeforeEach
    void setUp() {
        agentPlatform = mock(AgentPlatform.class);
        agentScope = mock(AgentScope.class);
        agentScopeBuilder = mock(AgentScopeBuilder.class);
        agentProcess = mock(AgentProcess.class);

        // Create a real Agent instance to avoid mocking data class copy()
        agent = new Agent(
                "test-agent",
                "test-provider",
                "1.0.0",
                "Test agent description",
                Collections.emptySet(),
                Collections.emptyList(),
                Collections.emptySet(),
                null
        );

        when(agentPlatform.getName()).thenReturn("test-platform");
        when(agentScopeBuilder.build()).thenReturn(agentScope);
        when(agentScope.createAgent(anyString(), anyString(), anyString())).thenReturn(agent);
    }

    @Test
    void defaultConstructor() {
        var invocation = new UtilityInvocation(agentPlatform);

        assertNotNull(invocation);
    }

    @Test
    void constructorWithProcessOptions() {
        var options = ProcessOptions.DEFAULT.withPlannerType(PlannerType.UTILITY);

        var invocation = new UtilityInvocation(agentPlatform, options);

        assertNotNull(invocation);
    }

    @Test
    void constructorWithAllParameters() {
        var options = ProcessOptions.DEFAULT.withPlannerType(PlannerType.UTILITY);

        var invocation = new UtilityInvocation(agentPlatform, options, agentScopeBuilder);

        assertNotNull(invocation);
    }

    @Test
    void withProcessOptions() {
        var invocation = new UtilityInvocation(agentPlatform);
        var newOptions = ProcessOptions.DEFAULT
                .withPlannerType(PlannerType.UTILITY)
                .withPrune(true);

        var updated = invocation.withProcessOptions(newOptions);

        assertNotNull(updated);
        assertNotSame(invocation, updated);
    }

    @Test
    void withScope() {
        var invocation = new UtilityInvocation(agentPlatform);

        var updated = invocation.withScope(agentScopeBuilder);

        assertNotNull(updated);
        assertNotSame(invocation, updated);
    }

    @Test
    void runAsyncWithVarargs() {
        when(agentPlatform.createAgentProcessFrom(
                any(Agent.class),
                argThat(po -> po.getPlannerType() == PlannerType.UTILITY),
                any(Object[].class)
        )).thenReturn(agentProcess);
        when(agentPlatform.start(agentProcess))
                .thenReturn(CompletableFuture.completedFuture(agentProcess));

        var invocation = new UtilityInvocation(agentPlatform, ProcessOptions.DEFAULT, agentScopeBuilder);
        var input = new TestInput("test");
        var future = invocation.runAsync(input);

        assertNotNull(future);
        assertEquals(agentProcess, future.join());
        verify(agentPlatform).createAgentProcessFrom(
                any(Agent.class),
                argThat(po -> po.getPlannerType() == PlannerType.UTILITY),
                eq(new Object[]{input})
        );
        verify(agentPlatform).start(agentProcess);
    }

    @Test
    void runAsyncWithMultipleVarargs() {
        when(agentPlatform.createAgentProcessFrom(
                any(Agent.class),
                any(ProcessOptions.class),
                any(Object[].class)
        )).thenReturn(agentProcess);
        when(agentPlatform.start(agentProcess))
                .thenReturn(CompletableFuture.completedFuture(agentProcess));

        var invocation = new UtilityInvocation(agentPlatform, ProcessOptions.DEFAULT, agentScopeBuilder);
        var input1 = new TestInput("test1");
        var input2 = new TestInput("test2");
        var future = invocation.runAsync(input1, input2);

        assertNotNull(future);
        verify(agentPlatform).createAgentProcessFrom(
                any(Agent.class),
                any(ProcessOptions.class),
                eq(new Object[]{input1, input2})
        );
    }

    @Test
    void runAsyncWithMap() {
        Map<String, Object> bindings = Map.of("key", "value");
        when(agentPlatform.createAgentProcess(
                any(Agent.class),
                argThat(po -> po.getPlannerType() == PlannerType.UTILITY),
                eq(bindings)
        )).thenReturn(agentProcess);
        when(agentPlatform.start(agentProcess))
                .thenReturn(CompletableFuture.completedFuture(agentProcess));

        var invocation = new UtilityInvocation(agentPlatform, ProcessOptions.DEFAULT, agentScopeBuilder);
        var future = invocation.runAsync(bindings);

        assertNotNull(future);
        assertEquals(agentProcess, future.join());
        verify(agentPlatform).createAgentProcess(
                any(Agent.class),
                argThat(po -> po.getPlannerType() == PlannerType.UTILITY),
                eq(bindings)
        );
        verify(agentPlatform).start(agentProcess);
    }

    @Test
    void overridesPlannerTypeToUtilityWhenNotSet() {
        // Use GOAP (non-utility) planner type
        var options = ProcessOptions.DEFAULT.withPlannerType(PlannerType.GOAP);

        when(agentPlatform.createAgentProcessFrom(
                any(Agent.class),
                argThat(po -> po.getPlannerType() == PlannerType.UTILITY),
                any(Object[].class)
        )).thenReturn(agentProcess);
        when(agentPlatform.start(agentProcess))
                .thenReturn(CompletableFuture.completedFuture(agentProcess));

        var invocation = new UtilityInvocation(agentPlatform, options, agentScopeBuilder);
        invocation.runAsync(new TestInput("test"));

        // Verify that the planner type was overridden to UTILITY
        verify(agentPlatform).createAgentProcessFrom(
                any(Agent.class),
                argThat(po -> po.getPlannerType() == PlannerType.UTILITY),
                any(Object[].class)
        );
    }

    @Test
    void preservesPlannerTypeWhenAlreadyUtility() {
        var options = ProcessOptions.DEFAULT.withPlannerType(PlannerType.UTILITY);

        when(agentPlatform.createAgentProcessFrom(
                any(Agent.class),
                argThat(po -> po.getPlannerType() == PlannerType.UTILITY),
                any(Object[].class)
        )).thenReturn(agentProcess);
        when(agentPlatform.start(agentProcess))
                .thenReturn(CompletableFuture.completedFuture(agentProcess));

        var invocation = new UtilityInvocation(agentPlatform, options, agentScopeBuilder);
        invocation.runAsync(new TestInput("test"));

        verify(agentPlatform).createAgentProcessFrom(
                any(Agent.class),
                argThat(po -> po.getPlannerType() == PlannerType.UTILITY),
                any(Object[].class)
        );
    }

    @Test
    void createsAgentFromScope() {
        when(agentPlatform.createAgentProcessFrom(
                any(Agent.class),
                any(ProcessOptions.class),
                any(Object[].class)
        )).thenReturn(agentProcess);
        when(agentPlatform.start(agentProcess))
                .thenReturn(CompletableFuture.completedFuture(agentProcess));

        var invocation = new UtilityInvocation(agentPlatform, ProcessOptions.DEFAULT, agentScopeBuilder);
        invocation.runAsync(new TestInput("test"));

        verify(agentScope).createAgent(
                eq("test-platform"),
                anyString(),
                eq("Platform utility agent")
        );
    }

    @Test
    void defaultScopeIsAgentPlatform() {
        // When scope is not provided, it defaults to agentPlatform
        // Since AgentPlatform extends AgentScope, we can test this by checking
        // that createAgent is called on agentPlatform when no scope is provided
        when(agentPlatform.build()).thenReturn(agentPlatform);
        when(agentPlatform.createAgent(anyString(), anyString(), anyString())).thenReturn(agent);
        when(agentPlatform.createAgentProcessFrom(
                any(Agent.class),
                any(ProcessOptions.class),
                any(Object[].class)
        )).thenReturn(agentProcess);
        when(agentPlatform.start(agentProcess))
                .thenReturn(CompletableFuture.completedFuture(agentProcess));

        var invocation = new UtilityInvocation(agentPlatform);
        invocation.runAsync(new TestInput("test"));

        verify(agentPlatform).createAgent(
                anyString(),
                anyString(),
                anyString()
        );
    }

    @Test
    void createdAgentIncludesNirvanaGoal() {
        when(agentPlatform.createAgentProcessFrom(
                argThat(a -> a.getGoals().stream().anyMatch(g -> g.getName().equals("Nirvana"))),
                any(ProcessOptions.class),
                any(Object[].class)
        )).thenReturn(agentProcess);
        when(agentPlatform.start(agentProcess))
                .thenReturn(CompletableFuture.completedFuture(agentProcess));

        var invocation = new UtilityInvocation(agentPlatform, ProcessOptions.DEFAULT, agentScopeBuilder);
        invocation.runAsync(new TestInput("test"));

        verify(agentPlatform).createAgentProcessFrom(
                argThat(a -> a.getGoals().stream().anyMatch(g -> g.getName().equals("Nirvana"))),
                any(ProcessOptions.class),
                any(Object[].class)
        );
    }

    record TestInput(String value) {
    }

}
