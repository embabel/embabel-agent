/*
 * Copyright 2024-2025 Embabel Pty Ltd.
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
package com.embabel.agent.core;

import com.embabel.agent.api.channel.DevNullOutputChannel;
import com.embabel.agent.api.channel.OutputChannel;
import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.api.event.AgenticEventListener;
import com.embabel.agent.core.support.InMemoryBlackboard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProcessOptionsBuilderTest {

    @Test
    void withers() {
        var identities = new Identities();
        var blackboard = new InMemoryBlackboard();
        var listener = AgenticEventListener.DevNull;
        var verbosity = new Verbosity(true, true, true, true);
        var budget = new Budget(1, 2, 3);
        var processControl = new ProcessControl(
                Delay.MEDIUM,
                Delay.LONG,
                budget.earlyTerminationPolicy()
        );

        var po = ProcessOptions.DEFAULT
                .withContextId("42")
                .withIdentities(identities)
                .withBlackboard(blackboard)
                .withVerbosity(verbosity)
                .withBudget(budget)
                .withProcessControl(processControl)
                .withPrune(true)
                .withListener(listener);

        assertEquals("42", po.getContextIdString());
        assertEquals(identities, po.getIdentities());
        assertEquals(blackboard, po.getBlackboard());

        assertTrue(po.getVerbosity().getShowPrompts());
        assertTrue(po.getVerbosity().getShowLlmResponses());
        assertTrue(po.getVerbosity().getDebug());
        assertTrue(po.getVerbosity().getShowPlanning());

        assertEquals(1, po.getBudget().getCost());
        assertEquals(2, po.getBudget().getActions());
        assertEquals(3, po.getBudget().getTokens());

        assertEquals(Delay.MEDIUM, po.getProcessControl().getToolDelay());
        assertEquals(Delay.LONG, po.getProcessControl().getOperationDelay());
        assertTrue(po.getPrune());
        assertEquals(List.of(listener), po.getListeners());
    }

    @Test
    void withContextIdUsingContextIdType() {
        // ContextId is a value class, so we use the String overload which creates the ContextId
        var po = ProcessOptions.DEFAULT.withContextId("test-context");

        assertEquals("test-context", po.getContextIdString());
    }

    @Test
    void withContextIdUsingString() {
        var po = ProcessOptions.DEFAULT.withContextId("string-context");

        assertEquals("string-context", po.getContextIdString());
    }

    @Test
    void withContextIdNull() {
        var po = ProcessOptions.DEFAULT
                .withContextId("initial")
                .withContextId((String) null);

        assertNull(po.getContextIdString());
    }

    @Test
    void getContextIdStringReturnsNullWhenNotSet() {
        assertNull(ProcessOptions.DEFAULT.getContextIdString());
    }

    @Test
    void defaultProcessControlUsesDefaultBudgetPolicy() {
        var po = ProcessOptions.DEFAULT;

        // Default should use Budget.DEFAULT early termination policy
        assertEquals(Delay.NONE, po.getProcessControl().getToolDelay());
        assertEquals(Delay.NONE, po.getProcessControl().getOperationDelay());
        assertEquals(Budget.DEFAULT.earlyTerminationPolicy(), po.getProcessControl().getEarlyTerminationPolicy());
    }

    @Test
    void processControlWithers() {
        var newPolicy = EarlyTerminationPolicy.maxActions(100);
        var control = new ProcessControl()
                .withToolDelay(Delay.MEDIUM)
                .withOperationDelay(Delay.LONG)
                .withEarlyTerminationPolicy(newPolicy);

        assertEquals(Delay.MEDIUM, control.getToolDelay());
        assertEquals(Delay.LONG, control.getOperationDelay());
        assertEquals(newPolicy, control.getEarlyTerminationPolicy());
    }

    @Test
    void processControlDefaultConstructor() {
        var control = new ProcessControl();

        assertEquals(Delay.NONE, control.getToolDelay());
        assertEquals(Delay.NONE, control.getOperationDelay());
        assertNotNull(control.getEarlyTerminationPolicy());
    }

    @Test
    void processControlConstructorWithAllParameters() {
        var policy = EarlyTerminationPolicy.maxActions(50);
        var control = new ProcessControl(Delay.MEDIUM, Delay.LONG, policy);

        assertEquals(Delay.MEDIUM, control.getToolDelay());
        assertEquals(Delay.LONG, control.getOperationDelay());
        assertEquals(policy, control.getEarlyTerminationPolicy());
    }

    @Test
    void withAdditionalEarlyTerminationPolicy() {
        var additionalPolicy = EarlyTerminationPolicy.maxTokens(5000);
        var po = ProcessOptions.DEFAULT.withAdditionalEarlyTerminationPolicy(additionalPolicy);

        // The policy should be added (combined with firstOf)
        assertNotNull(po.getProcessControl().getEarlyTerminationPolicy());
    }

    @Test
    void identitiesWithers() {
        var identities = Identities.DEFAULT
                .withForUser(null)
                .withRunAs(null);

        assertEquals(Identities.DEFAULT, identities);
    }

    @Test
    void withListeners() {
        var listener1 = AgenticEventListener.DevNull;
        var listener2 = AgenticEventListener.DevNull;

        var po = ProcessOptions.DEFAULT
                .withListeners(List.of(listener1, listener2));

        assertEquals(List.of(listener1, listener2), po.getListeners());
    }

    @Test
    void withListener() {
        var listener = AgenticEventListener.DevNull;

        var po = ProcessOptions.DEFAULT.withListener(listener);

        assertEquals(List.of(listener), po.getListeners());
    }

    @Test
    void withListenerAccumulates() {
        var listener1 = AgenticEventListener.DevNull;
        var listener2 = AgenticEventListener.DevNull;

        var po = ProcessOptions.DEFAULT
                .withListener(listener1)
                .withListener(listener2);

        assertEquals(List.of(listener1, listener2), po.getListeners());
    }

    @Test
    void withOutputChannel() {
        OutputChannel customChannel = DevNullOutputChannel.INSTANCE;

        var po = ProcessOptions.DEFAULT.withOutputChannel(customChannel);

        assertEquals(customChannel, po.getOutputChannel());
    }

    @Test
    void withPlannerType() {
        var po = ProcessOptions.DEFAULT.withPlannerType(PlannerType.UTILITY);

        assertEquals(PlannerType.UTILITY, po.getPlannerType());
    }

    @Test
    void withBlackboardNull() {
        var blackboard = new InMemoryBlackboard();
        var po = ProcessOptions.DEFAULT
                .withBlackboard(blackboard)
                .withBlackboard(null);

        assertNull(po.getBlackboard());
    }

    @Test
    void withIdentities() {
        var identities = new Identities();

        var po = ProcessOptions.DEFAULT.withIdentities(identities);

        assertEquals(identities, po.getIdentities());
    }

    @Test
    void withVerbosity() {
        var verbosity = new Verbosity(true, false, true, false);

        var po = ProcessOptions.DEFAULT.withVerbosity(verbosity);

        assertEquals(verbosity, po.getVerbosity());
    }

    @Test
    void withBudget() {
        var budget = new Budget(5.0, 100, 500000);

        var po = ProcessOptions.DEFAULT.withBudget(budget);

        assertEquals(budget, po.getBudget());
    }

    @Test
    void withProcessControl() {
        var budget = Budget.DEFAULT;
        var control = new ProcessControl(Delay.LONG, Delay.MEDIUM, budget.earlyTerminationPolicy());

        var po = ProcessOptions.DEFAULT.withProcessControl(control);

        assertEquals(control, po.getProcessControl());
    }

    @Test
    void withPrune() {
        var po = ProcessOptions.DEFAULT.withPrune(true);

        assertTrue(po.getPrune());
    }

    @Test
    void withPruneFalse() {
        var po = ProcessOptions.DEFAULT
                .withPrune(true)
                .withPrune(false);

        assertFalse(po.getPrune());
    }

    // Early Termination Policy tests

    @Test
    void earlyTerminationPolicyMaxActions() {
        var policy = EarlyTerminationPolicy.maxActions(25);

        assertNotNull(policy);
        assertEquals("MaxActionsEarlyTerminationPolicy", policy.getName());
    }

    @Test
    void earlyTerminationPolicyMaxTokens() {
        var policy = EarlyTerminationPolicy.maxTokens(10000);

        assertNotNull(policy);
        assertEquals("MaxTokensEarlyTerminationPolicy", policy.getName());
    }

    @Test
    void earlyTerminationPolicyHardBudgetLimit() {
        var policy = EarlyTerminationPolicy.hardBudgetLimit(5.0);

        assertNotNull(policy);
        assertEquals("MaxCostEarlyTerminationPolicy", policy.getName());
    }

    @Test
    void earlyTerminationPolicyOnStuck() {
        var policy = EarlyTerminationPolicy.getON_STUCK();

        assertNotNull(policy);
        assertEquals("OnStuckEarlyTerminationPolicy", policy.getName());
    }

    @Test
    void earlyTerminationPolicyFirstOf() {
        var policy1 = EarlyTerminationPolicy.maxActions(10);
        var policy2 = EarlyTerminationPolicy.maxTokens(1000);
        var combined = EarlyTerminationPolicy.firstOf(policy1, policy2);

        assertNotNull(combined);
        assertEquals("FirstOfEarlyTerminationPolicy", combined.getName());
    }

    @Test
    void processControlWithAdditionalEarlyTerminationPolicy() {
        var initialPolicy = EarlyTerminationPolicy.maxActions(50);
        var additionalPolicy = EarlyTerminationPolicy.getON_STUCK();

        var control = new ProcessControl()
                .withEarlyTerminationPolicy(initialPolicy)
                .withAdditionalEarlyTerminationPolicy(additionalPolicy);

        assertNotNull(control.getEarlyTerminationPolicy());
        assertEquals("FirstOfEarlyTerminationPolicy", control.getEarlyTerminationPolicy().getName());
    }

}
