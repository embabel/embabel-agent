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
package com.embabel.agent.api.tool;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link ToolCallContext} is idiomatic to use from Java.
 */
class ToolCallContextJavaTest {

    @Nested
    class FactoryMethods {

        @Test
        void createFromMap() {
            var ctx = ToolCallContext.of(Map.of("token", "abc", "tenant", "acme"));
            assertEquals("abc", ctx.<String>get("token"));
            assertEquals("acme", ctx.<String>get("tenant"));
            assertFalse(ctx.isEmpty());
        }

        @Test
        void emptyContextIsAvailable() {
            var ctx = ToolCallContext.EMPTY;
            assertTrue(ctx.isEmpty());
            assertNull(ctx.<String>get("anything"));
        }
    }

    @Nested
    class Merge {

        @Test
        void mergeGivesOtherPrecedence() {
            var base = ToolCallContext.of(Map.of("key", "old", "extra", "kept"));
            var override = ToolCallContext.of(Map.of("key", "new"));
            var merged = base.merge(override);
            assertEquals("new", merged.<String>get("key"));
            assertEquals("kept", merged.<String>get("extra"));
        }
    }

    @Nested
    class ThreadLocal {

        @Test
        void setAndCurrentRoundTrip() {
            var ctx = ToolCallContext.of(Map.of("id", "123"));
            try {
                ToolCallContext.set(ctx);
                assertEquals(ctx, ToolCallContext.current());
            } finally {
                ToolCallContext.remove();
            }
        }

        @Test
        void currentReturnsEmptyWhenUnset() {
            ToolCallContext.remove();
            assertEquals(ToolCallContext.EMPTY, ToolCallContext.current());
        }
    }

    @Nested
    class GetOrDefault {

        @Test
        void returnsDefaultForMissingKey() {
            var ctx = ToolCallContext.of(Map.of("a", 1));
            assertEquals("fallback", ctx.getOrDefault("missing", "fallback"));
        }

        @Test
        void returnsValueWhenPresent() {
            var ctx = ToolCallContext.of(Map.of("a", 1));
            assertEquals(1, ctx.<Integer>getOrDefault("a", 99));
        }
    }

    @Nested
    class WithProcessOptions {

        @Test
        void processOptionsWitherAcceptsMap() {
            var options = new com.embabel.agent.core.ProcessOptions()
                .withToolCallContext(Map.of("authToken", "bearer-xyz"));
            assertEquals("bearer-xyz", options.getToolCallContext().<String>get("authToken"));
        }
    }
}
