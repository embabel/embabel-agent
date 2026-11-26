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

import com.embabel.ux.form.Form;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Java constructor overloads for fresh and restored form requests.
 */
class FormBindingRequestJavaTest {

    private final Form form = new Form("Approval", List.of(), "approval-form");

    @Test
    void createsFreshRequestsThroughExistingJavaOverloads() {
        ValidationError error = new ValidationError() {};
        Instant before = Instant.now();
        List<FormBindingRequest<String>> requests = List.of(
            new FormBindingRequest<>(form, String.class),
            new FormBindingRequest<>(form, String.class, "pending"),
            new FormBindingRequest<>(form, String.class, "pending", List.of(error)),
            new FormBindingRequest<>(form, String.class, "pending", List.of(error), true),
            new FormBindingRequest<>(form, String.class, "pending", List.of(error), true, "approval")
        );

        Instant after = Instant.now();

        for (FormBindingRequest<String> request : requests) {
            assertSame(form, request.getPayload());
            assertSame(String.class, request.getOutputClass());
            assertEquals(request.getId(), UUID.fromString(request.getId()).toString());
            assertFalse(request.getTimestamp().isBefore(before));
            assertFalse(request.getTimestamp().isAfter(after));
        }
        assertNull(requests.get(0).getPopulation());
        assertTrue(requests.get(0).getValidationErrors().isEmpty());
        assertFalse(requests.get(0).persistent());
        assertNull(requests.get(0).getBindingName());
        assertEquals("pending", requests.get(1).getPopulation());
        assertEquals(List.of(error), requests.get(2).getValidationErrors());
        assertTrue(requests.get(3).persistent());
        assertEquals("approval", requests.get(4).getBindingName());
        assertNotEquals(requests.get(0).getId(), requests.get(4).getId());
    }

    @Test
    void createsRestoredRequestWithSavedIdentityThroughJava() {
        Instant savedAt = Instant.parse("2026-01-01T12:00:00Z");
        FormBindingRequest<String> restored = new FormBindingRequest<>(
            form, String.class, "pending", List.of(), true, "approval", "saved-request", savedAt
        );

        assertEquals("saved-request", restored.getId());
        assertEquals(savedAt, restored.getTimestamp());
    }
}
