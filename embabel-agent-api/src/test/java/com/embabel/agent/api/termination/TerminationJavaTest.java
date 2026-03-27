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
import com.embabel.agent.api.tool.TerminationException;
import com.embabel.agent.api.tool.ToolControlFlowSignal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
    void terminationExceptionBaseClassAllowsCatchingBoth() {
        // Both exception types extend TerminationException
        TerminateAgentException agentEx = new TerminateAgentException("agent reason");
        TerminateActionException actionEx = new TerminateActionException("action reason");

        assertInstanceOf(TerminationException.class, agentEx);
        assertInstanceOf(TerminationException.class, actionEx);

        // Can catch both with single catch block
        int caughtCount = 0;
        for (RuntimeException ex : new RuntimeException[]{agentEx, actionEx}) {
            try {
                throw ex;
            } catch (TerminationException e) {
                caughtCount++;
                assertNotNull(e.getReason());
            }
        }
        assertEquals(2, caughtCount);
    }

    // Note: terminateAgent/terminateAction methods are now on AgentProcess interface.
    // Java interop for these is tested via the agentic integration tests in TerminationAgenticTest.
}
