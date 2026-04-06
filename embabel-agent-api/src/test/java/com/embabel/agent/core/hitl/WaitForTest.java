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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link WaitFor} Java syntax sugar for HITL operations.
 * Tests verify delegation to underlying WaitKt Kotlin functions and proper exception handling.
 */
class WaitForTest {

    record TestData(String name, int value) {}

    @Test
    @DisplayName("Should have private constructor to prevent instantiation")
    void hasPrivateConstructor() throws Exception {
        // Arrange
        Constructor<WaitFor> constructor = WaitFor.class.getDeclaredConstructor();
        // Assert
        assertTrue(Modifier.isPrivate(constructor.getModifiers()),
            "Constructor must be private to prevent instantiation");
    }

    @Test
    @DisplayName("Should throw AwaitableResponseException when calling formSubmission")
    void formSubmissionThrowsAwaitableResponseException() {
        // Arrange & Act & Assert
        AwaitableResponseException exception = assertThrows(AwaitableResponseException.class,
            () -> WaitFor.formSubmission("Test Form", TestData.class),
            "formSubmission should throw AwaitableResponseException");
        // Assert
        assertNotNull(exception.getAwaitable(), "Exception should contain an awaitable");
        assertInstanceOf(FormBindingRequest.class, exception.getAwaitable(),
            "Awaitable should be a FormBindingRequest");
    }

    @Test
    @DisplayName("Should throw AwaitableResponseException when calling confirmation")
    void confirmationThrowsAwaitableResponseException() {
        // Arrange
        String testValue = "test-value";
        String description = "Confirm this action";
        // Act & Assert
        AwaitableResponseException exception = assertThrows(AwaitableResponseException.class,
            () -> WaitFor.confirmation(testValue, description),
            "confirmation should throw AwaitableResponseException");
        // Assert
        assertNotNull(exception.getAwaitable(), "Exception should contain an awaitable");
        assertInstanceOf(ConfirmationRequest.class, exception.getAwaitable(),
            "Awaitable should be a ConfirmationRequest");
    }

    @Test
    @DisplayName("Should throw AwaitableResponseException when calling awaitable")
    void awaitableThrowsAwaitableResponseException() {
        // Arrange
        Awaitable<String, ?> testAwaitable = new ConfirmationRequest<>("test", "description");
        // Act & Assert
        AwaitableResponseException exception = assertThrows(AwaitableResponseException.class,
            () -> WaitFor.awaitable(testAwaitable),
            "awaitable should throw AwaitableResponseException");
        // Assert
        assertSame(testAwaitable, exception.getAwaitable(),
            "Exception should contain the same awaitable instance");
    }

    @Test
    @DisplayName("Should accept null title in formSubmission and use default")
    void formSubmissionAcceptsNullTitle() {
        // Act & Assert
        AwaitableResponseException exception = assertThrows(AwaitableResponseException.class,
            () -> WaitFor.formSubmission(null, TestData.class),
            "formSubmission should throw AwaitableResponseException even with null title");
        // Assert
        FormBindingRequest<?> request = (FormBindingRequest<?>) exception.getAwaitable();
        assertNotNull(request.getPayload(), "Request should have a generated form with default title");
        assertTrue(request.getPayload().getTitle().contains("TestData"),
            "Default title should reference the class name");
    }

    @Test
    @DisplayName("Should throw NullPointerException when formSubmission receives null class")
    void formSubmissionThrowsOnNullClass() {
        // Act & Assert
        assertThrows(NullPointerException.class,
            () -> WaitFor.formSubmission("Test", null),
            "formSubmission should reject null class due to @NonNull annotation");
    }

    @Test
    @DisplayName("Should throw NullPointerException when confirmation receives null payload")
    void confirmationThrowsOnNullPayload() {
        // Act & Assert
        assertThrows(NullPointerException.class,
            () -> WaitFor.confirmation(null, "description"),
            "confirmation should reject null payload due to @NonNull annotation");
    }

    @Test
    @DisplayName("Should throw NullPointerException when confirmation receives null description")
    void confirmationThrowsOnNullDescription() {
        // Act & Assert
        assertThrows(NullPointerException.class,
            () -> WaitFor.confirmation("test", null),
            "confirmation should reject null description due to @NonNull annotation");
    }

    @Test
    @DisplayName("Should throw NullPointerException when awaitable receives null awaitable")
    void awaitableThrowsOnNullAwaitable() {
        // Act & Assert
        assertThrows(NullPointerException.class,
            () -> WaitFor.awaitable(null),
            "awaitable should reject null awaitable due to @NonNull annotation");
    }

    @Test
    @DisplayName("Should preserve FormBindingRequest details from formSubmission")
    void formSubmissionPreservesRequestDetails() {
        // Arrange
        String expectedTitle = "Payment Form";
        // Act & Assert
        AwaitableResponseException exception = assertThrows(AwaitableResponseException.class,
            () -> WaitFor.formSubmission(expectedTitle, TestData.class));
        // Assert
        FormBindingRequest<?> request = (FormBindingRequest<?>) exception.getAwaitable();
        assertNotNull(request.getPayload(), "Request should have a generated form");
        assertEquals(TestData.class, request.getOutputClass(),
            "Request output class should match provided class");
        assertFalse(request.persistent(),
            "Request should not be persistent by default");
    }

    @Test
    @DisplayName("Should preserve ConfirmationRequest details from confirmation")
    void confirmationPreservesRequestDetails() {
        // Arrange
        Integer testValue = 42;
        String expectedMessage = "Proceed with operation?";
        // Act & Assert
        AwaitableResponseException exception = assertThrows(AwaitableResponseException.class,
            () -> WaitFor.confirmation(testValue, expectedMessage));
        // Assert
        @SuppressWarnings("unchecked")
        ConfirmationRequest<Integer> request = (ConfirmationRequest<Integer>) exception.getAwaitable();
        assertEquals(testValue, request.getPayload(), "Request should preserve payload value");
        assertEquals(expectedMessage, request.getMessage(),
            "Request should preserve message");
    }
}
