/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.api.common.workflow.control;

import com.embabel.agent.core.AgentProcessStatusCode;
import com.embabel.agent.core.ProcessOptions;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.testing.integration.IntegrationTestUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleAgentBuilderTest {

    record Person(String name, int age) {
    }

    record Age(int years) {
    }

    record Combined(String name, int years) {
    }

    @Nested
    class Supplier {

        @Test
        void testConsensusOfOneSimpleGenerator() {
            var agent = SimpleAgentBuilder
                    .returning(Age.class)
                    .running(tac -> new Age(55))
                    .buildAgent("name", "description");
            var ap = IntegrationTestUtils.dummyAgentPlatform();
            var result = ap.runAgentFrom(
                    agent,
                    ProcessOptions.getDEFAULT(),
                    Map.of("it", new UserInput("input"))
            );
            assertEquals(AgentProcessStatusCode.COMPLETED, result.getStatus());
            assertTrue(result.lastResult() instanceof Age);
            var age = (Age) result.lastResult();
            assertEquals(55, age.years(), "Expected age");
        }

    }

    @Nested
    class ConsumerAndSupplier {

        @Test
        void testConsensusOfOneSimpleGenerator() {
            var agent = SimpleAgentBuilder
                    .returning(Person.class)
                    .consuming(Combined.class)
                    .running(tac -> new Person("Geoff", 55))
                    .buildAgent("name", "description");
            var ap = IntegrationTestUtils.dummyAgentPlatform();
            var result = ap.runAgentFrom(
                    agent,
                    ProcessOptions.getDEFAULT(),
                    Map.of("it", new Combined("James", 55))
            );
            assertEquals(AgentProcessStatusCode.COMPLETED, result.getStatus());
            assertTrue(result.lastResult() instanceof Person);
            var person = (Person) result.lastResult();
            assertEquals("Geoff", person.name(), "Expected name");
            assertEquals(55, person.age(), "Expected age");
        }

    }
}