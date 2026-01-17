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
package com.embabel.agent.test.unit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java tests for FakeOperationContext to verify correct interop.
 */
public class FakeOperationContextJavaTest {

    @Test
    public void testInteractionIdAccessFromJava() {
        var context = FakeOperationContext.create();
        context.expectResponse("test result");

        var result = context.ai()
                .withDefaultLlm()
                .withId("classify-intent")
                .createObject("Test prompt", String.class);

        assertEquals("test result", result);
        assertEquals(1, context.getLlmInvocations().size());

        var invocation = context.getLlmInvocations().get(0);
        var interaction = invocation.getInteraction();

        // Verify the ID is accessible from Java
        // For value classes, Java sees the underlying type directly
        assertEquals("classify-intent", interaction.getId());
    }

    @Test
    public void testMultipleInvocations() {
        var context = FakeOperationContext.create();
        context.expectResponse("first");
        context.expectResponse("second");

        context.ai().withDefaultLlm().withId("op-1")
                .createObject("First", String.class);
        context.ai().withDefaultLlm().withId("op-2")
                .createObject("Second", String.class);

        assertEquals(2, context.getLlmInvocations().size());
        assertEquals("op-1", context.getLlmInvocations().get(0).getInteraction().getId());
        assertEquals("op-2", context.getLlmInvocations().get(1).getInteraction().getId());
    }
}
