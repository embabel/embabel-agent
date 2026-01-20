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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Java interoperability tests for ReplanRequestedException.
 */
class ReplanRequestedExceptionJavaTest {

    @Test
    void createWithReasonOnly() {
        ReplanRequestedException exception = new ReplanRequestedException(
            "Need to replan"
        );

        assertEquals("Need to replan", exception.getReason());
        assertNotNull(exception.getBlackboardUpdater());
    }

    @Test
    void createWithBlackboardUpdater() {
        ReplanRequestedException exception = new ReplanRequestedException(
            "Routing decision",
            (blackboard) -> {
                blackboard.set("intent", "support");
                blackboard.set("confidence", 0.95);
            }
        );

        assertEquals("Routing decision", exception.getReason());
        assertNotNull(exception.getBlackboardUpdater());
    }
}
