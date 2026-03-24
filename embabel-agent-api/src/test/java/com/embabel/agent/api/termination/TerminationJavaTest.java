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
package com.embabel.agent.api.termination;

import com.embabel.agent.api.common.TerminationScope;
import com.embabel.agent.api.common.TerminationSignal;
import com.embabel.agent.api.tool.TerminateActionException;
import com.embabel.agent.api.tool.TerminateAgentException;
import com.embabel.agent.api.tool.ToolControlFlowSignal;
import com.embabel.agent.core.ProcessContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Java interoperability tests for Termination API.
 * Validates that the API is usable from Java code.
 */
class TerminationJavaTest {

    @Test
    void terminateAgentExceptionConstruction() {
        TerminateAgentException exception = new TerminateAgentException("Critical error");

        assertEquals("Critical error", exception.getReason());
        assertEquals("Critical error", exception.getMessage());
        assertInstanceOf(ToolControlFlowSignal.class, exception);
    }

    @Test
    void terminateActionExceptionConstruction() {
        TerminateActionException exception = new TerminateActionException("Skip action");

        assertEquals("Skip action", exception.getReason());
        assertEquals("Skip action", exception.getMessage());
        assertInstanceOf(ToolControlFlowSignal.class, exception);
    }

    @Test
    void terminationSignalConstruction() {
        TerminationSignal agentSignal = new TerminationSignal(
            TerminationScope.AGENT,
            "Stop the agent"
        );

        assertEquals(TerminationScope.AGENT, agentSignal.getScope());
        assertEquals("Stop the agent", agentSignal.getReason());

        TerminationSignal actionSignal = new TerminationSignal(
            TerminationScope.ACTION,
            "Stop the action"
        );

        assertEquals(TerminationScope.ACTION, actionSignal.getScope());
        assertEquals("Stop the action", actionSignal.getReason());
    }

    @Test
    void terminationScopeValues() {
        assertEquals("agent", TerminationScope.AGENT.getValue());
        assertEquals("action", TerminationScope.ACTION.getValue());
    }

    @Test
    void terminateAgentExtensionFunction() {
        // Verify the static method is accessible from Java via @JvmName("Termination")
        ProcessContext mockContext = mock(ProcessContext.class);
        com.embabel.agent.core.Blackboard mockBlackboard = mock(com.embabel.agent.core.Blackboard.class);
        when(mockContext.getBlackboard()).thenReturn(mockBlackboard);

        // Call the extension function as a static method
        Termination.terminateAgent(mockContext, "Graceful shutdown");

        // Verify signal was set on blackboard
        verify(mockBlackboard).set(eq("__termination_signal__"), any(TerminationSignal.class));
    }

    @Test
    void terminateActionExtensionFunction() {
        ProcessContext mockContext = mock(ProcessContext.class);
        com.embabel.agent.core.Blackboard mockBlackboard = mock(com.embabel.agent.core.Blackboard.class);
        when(mockContext.getBlackboard()).thenReturn(mockBlackboard);

        // Call the extension function as a static method
        Termination.terminateAction(mockContext, "Skip remaining work");

        // Verify signal was set on blackboard
        verify(mockBlackboard).set(eq("__termination_signal__"), any(TerminationSignal.class));
    }
}
