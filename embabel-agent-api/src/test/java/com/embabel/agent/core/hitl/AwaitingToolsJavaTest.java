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
package com.embabel.agent.core.hitl;

import com.embabel.agent.api.tool.Tool;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java interoperability tests for HITL awaiting tools.
 * Tests construction and basic behavior without requiring AgentProcess setup.
 */
class AwaitingToolsJavaTest {

    private Tool createMockTool(String name) {
        return Tool.create(name, "Test tool", input -> Tool.Result.text("executed"));
    }

    record PaymentDetails(String cardNumber, double amount) {}

    @Nested
    class ConfirmingToolTests {

        @Test
        void createsWithMessageProvider() {
            Tool delegate = createMockTool("test");
            var tool = new ConfirmingTool(delegate, input -> "Confirm action?");

            assertNotNull(tool);
            assertEquals("test", tool.getDefinition().getName());
        }

        @Test
        void throwsAwaitableResponseException() {
            Tool delegate = createMockTool("test");
            var tool = new ConfirmingTool(delegate, input -> "Confirm?");

            var exception = assertThrows(AwaitableResponseException.class, () -> {
                tool.call("{}");
            });

            assertInstanceOf(ConfirmationRequest.class, exception.getAwaitable());
        }
    }

    @Nested
    class ConditionalAwaitingToolTests {

        @Test
        void createsWithDecider() {
            Tool delegate = createMockTool("test");
            // Just test construction - execution requires AgentProcess
            var tool = new ConditionalAwaitingTool(delegate, context -> null);

            assertNotNull(tool);
            assertEquals("test", tool.getDefinition().getName());
            assertEquals(delegate.getDefinition().getDescription(), tool.getDefinition().getDescription());
        }
    }

    @Nested
    class TypeRequestingToolTests {

        @Test
        void createsWithTypeClass() {
            Tool delegate = createMockTool("payment");
            var tool = new TypeRequestingTool<>(delegate, PaymentDetails.class, input -> null);

            assertNotNull(tool);
            assertEquals("payment", tool.getDefinition().getName());
        }

        @Test
        void throwsAwaitableResponseExceptionWithTypeRequest() {
            Tool delegate = createMockTool("payment");
            var tool = new TypeRequestingTool<>(delegate, PaymentDetails.class, input -> "Enter payment");

            var exception = assertThrows(AwaitableResponseException.class, () -> {
                tool.call("{}");
            });

            var awaitable = exception.getAwaitable();
            assertInstanceOf(TypeRequest.class, awaitable);

            @SuppressWarnings("unchecked")
            var typeRequest = (TypeRequest<PaymentDetails>) awaitable;
            assertEquals(PaymentDetails.class, typeRequest.getType());
            assertEquals("Enter payment", typeRequest.getMessage());
        }
    }

    @Nested
    class TypeRequestTests {

        @Test
        void createsWithTypeOnly() {
            var request = new TypeRequest<>(PaymentDetails.class);

            assertEquals(PaymentDetails.class, request.getType());
            assertNull(request.getMessage());
            assertNull(request.getHint());
        }

        @Test
        void createsWithAllParameters() {
            var hint = new PaymentDetails("****1234", 100.0);
            var request = new TypeRequest<>(
                    PaymentDetails.class,
                    "Please provide payment",
                    hint,
                    false
            );

            assertEquals(PaymentDetails.class, request.getType());
            assertEquals("Please provide payment", request.getMessage());
            assertEquals(hint, request.getHint());
        }
    }
}
