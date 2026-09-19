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
package com.embabel.agent.core;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link ToolNamingStrategy} is public API in core, so a Java caller must be able to use it
 * without Kotlin helpers. The nullable owner in particular has to accept a plain Java null.
 */
class ToolNamingStrategyJavaTest {

    @Nested
    class LegacyNameOnly {

        @Test
        void keepsTheExistingToolName() {
            assertEquals("search", ToolNamingStrategy.LEGACY_NAME_ONLY.nameFor("AgentA", "search"));
        }

        @Test
        void acceptsANullOwner() {
            assertEquals("search", ToolNamingStrategy.LEGACY_NAME_ONLY.nameFor(null, "search"));
        }
    }

    @Nested
    class FullyQualified {

        @Test
        void qualifiesWithTheOwner() {
            assertEquals("AgentA-search", ToolNamingStrategy.FULLY_QUALIFIED.nameFor("AgentA", "search"));
        }

        @Test
        void acceptsANullOwner() {
            assertEquals("search", ToolNamingStrategy.FULLY_QUALIFIED.nameFor(null, "search"));
        }

        @Test
        void keepsDistinctOwnersDistinct() {
            assertNotEquals(
                    ToolNamingStrategy.FULLY_QUALIFIED.nameFor("AgentA", "search"),
                    ToolNamingStrategy.FULLY_QUALIFIED.nameFor("AgentB", "search"));
        }
    }
}
