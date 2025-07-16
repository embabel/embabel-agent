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
package com.embabel.agent.api.common.autonomy;

import com.embabel.agent.core.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AgentInvocationJavaTest {

    private final AgentPlatform agentPlatform = mock(AgentPlatform.class);

    private final Agent agent = mock(Agent.class);

    private final AgentProcess agentProcess = mock(AgentProcess.class);

    @Test
    public void defaultVarargsInvocation() {
        Foo foo = new Foo();
        Bar expected = new Bar();
        Goal goal = Goal.createInstance("Test goal", Bar.class);
        AgentInvocation<Bar> invocation =
                AgentInvocation.create(agentPlatform, Bar.class);

        when(agentPlatform.agents()).thenReturn(List.of(agent));
        when(agent.getGoals()).thenReturn(Set.of(goal));
        when(agentPlatform.createAgentProcessFrom(
                eq(agent),
                any(ProcessOptions.class),
                eq(foo)
        )).thenReturn(agentProcess);
        when(agentPlatform.start(agentProcess))
                .thenReturn(CompletableFuture.completedFuture(agentProcess));
        when(agentProcess.last(Bar.class))
                .thenReturn(expected);

        Bar actual = invocation.invoke(foo);
        assertEquals(expected, actual);
    }

    @Test
    public void defaultMapInvocation() {
        Foo foo = new Foo();
        Map<String, Object> bindings = Map.of("id", foo);
        Bar expected = new Bar();
        Goal goal = Goal.createInstance("Test goal", Bar.class);
        AgentInvocation<Bar> invocation =
                AgentInvocation.create(agentPlatform, Bar.class);

        when(agentPlatform.agents()).thenReturn(List.of(agent));
        when(agent.getGoals()).thenReturn(Set.of(goal));
        when(agentPlatform.createAgentProcess(
                eq(agent),
                any(ProcessOptions.class),
                eq(bindings)
        )).thenReturn(agentProcess);
        when(agentPlatform.start(agentProcess))
                .thenReturn(CompletableFuture.completedFuture(agentProcess));
        when(agentProcess.last(Bar.class))
                .thenReturn(expected);

        Bar actual = invocation.invoke(bindings);
        assertEquals(expected, actual);
    }

    @Test
    public void customProcessingOptions() {
        Goal goal = Goal.createInstance("Test goal", Bar.class);
        AgentInvocation<Bar> invocation =
                AgentInvocation.builder(agentPlatform)
                        .options(options -> options
                                .verbosity(verbosity -> verbosity
                                        .debug(true)))
                        .build(Bar.class);

        when(agentPlatform.agents()).thenReturn(List.of(agent));
        when(agent.getGoals()).thenReturn(Set.of(goal));
        when(agentPlatform.createAgentProcessFrom(
                eq(agent),
                assertArg(processOptions -> assertTrue(processOptions.getVerbosity().getDebug())),
                any()
        )).thenReturn(agentProcess);
        when(agentPlatform.start(agentProcess))
                .thenReturn(CompletableFuture.completedFuture(agentProcess));
        when(agentProcess.last(Bar.class))
                .thenReturn(new Bar());

        invocation.invoke(new Foo());
    }

    static class Foo {
    }

    static class Bar {
    }

}
