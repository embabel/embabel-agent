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
package com.embabel.agent.api.common.workflow;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.support.AgentMetadataReader;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.api.common.workflow.loop.RepeatUntilAcceptableBuilder;
import com.embabel.agent.api.common.workflow.loop.RepeatUntilBuilder;
import com.embabel.agent.api.common.workflow.loop.TextFeedback;
import com.embabel.agent.core.AgentProcessStatusCode;
import com.embabel.agent.core.ProcessOptions;
import com.embabel.agent.core.Verbosity;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.testing.integration.IntegrationTestUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RepeatUntilBuilderTest {

    record Person(String name, int age) {
    }

    public static class Report {
        private final String content;

        public Report(String content) {
            this.content = content;
        }

        public String getContent() {
            return content;
        }

        @Override
        public String toString() {
            return "Report{" +
                    "content='" + content + '\'' +
                    '}';
        }
    }

    @Nested
    class Supplier {

        @Test
        void terminatesItself() {
            AgentMetadataReader reader = new AgentMetadataReader();
            var agent = (com.embabel.agent.core.Agent) reader.createAgentMetadata(new RepeatUntilTerminatesOK());
            var ap = IntegrationTestUtils.dummyAgentPlatform();
            var result = ap.runAgentFrom(
                    agent,
                    ProcessOptions.getDEFAULT(),
                    Map.of("it", new UserInput("input"))
            );
            assertEquals(AgentProcessStatusCode.COMPLETED, result.getStatus());
            assertTrue(result.lastResult() instanceof Report);
            // Doesn't work as it was only bound to subprocess, not the main process
//        var attemptHistory = result.last(AttemptHistory.class);
//        assertNotNull(attemptHistory, "Expected AttemptHistory in result: " + result.getObjects());
//        assertEquals(3, attemptHistory.attempts().size(), "Expected 3 attempts due to max iterations");
        }

        @Test
        void doesNotTerminateItself() {
            AgentMetadataReader reader = new AgentMetadataReader();
            var agent = (com.embabel.agent.core.Agent) reader.createAgentMetadata(new RepeatUntilDoesNotTerminate());
            var ap = IntegrationTestUtils.dummyAgentPlatform();
            var result = ap.runAgentFrom(
                    agent,
                    ProcessOptions.getDEFAULT(),
                    Map.of("it", new UserInput("input"))
            );
            assertEquals(AgentProcessStatusCode.COMPLETED, result.getStatus());
            assertTrue(result.lastResult() instanceof Report);
//        var attemptHistory = result.last(AttemptHistory.class);
//        assertNotNull(attemptHistory, "Expected AttemptHistory in result: " + result.getObjects());
//        assertEquals(3, attemptHistory.attempts().size(),
//                "Expected 3 attempts due to max iterations: " + result.getObjects());
        }

        @Test
        void terminatesItselfAgent() {
            var agent = RepeatUntilAcceptableBuilder
                    .returning(Report.class)
                    .withMaxIterations(3)
                    .repeating(
                            tac -> {
                                return new Report("thing-" + tac.getInput().attempts().size());
                            })
                    .withEvaluator(
                            ctx -> {
                                assertNotNull(ctx.getInput().resultToEvaluate(),
                                        "Last result must be available to evaluator");
                                return new TextFeedback(0.5, "feedback");
                            })
                    .withAcceptanceCriteria(f -> true)
                    .buildAgent("myAgent", "This is a very good agent");

            var ap = IntegrationTestUtils.dummyAgentPlatform();
            var result = ap.runAgentFrom(
                    agent,
                    ProcessOptions.builder()
                            .verbosity(Verbosity.builder().showPlanning(true).build())
                            .build(),
                    Map.of("it", new UserInput("input"))
            );
            assertEquals(AgentProcessStatusCode.COMPLETED, result.getStatus());
            assertTrue(result.lastResult() instanceof Report,
                    "Report was bound: " + result.getObjects());
        }


        @Agent(description = "evaluator test")
        public static class RepeatUntilTerminatesOK {

            @AchievesGoal(description = "Creating a report")
            @Action
            public Report report(UserInput userInput, ActionContext context) {
                final int[] count = {0};
                var eo = RepeatUntilBuilder
                        .returning(Report.class)
                        .withMaxIterations(3)
                        .repeating(
                                tac -> {
                                    count[0]++;
                                    return new Report("thing-" + count[0]);
                                })
                        .until(f -> true)
                        .build();
                return context.asSubProcess(
                        Report.class,
                        eo);
            }

        }

        @Agent(description = "evaluator test")
        public static class RepeatUntilDoesNotTerminate {

            @AchievesGoal(description = "Creating a report")
            @Action
            public Report report(UserInput userInput, ActionContext context) {
                final int[] count = {0};
                var eo = RepeatUntilBuilder
                        .returning(Report.class)
                        .withMaxIterations(3)
                        .repeating(
                                tac -> {
                                    count[0]++;
                                    return new Report("thing-" + count[0]);
                                })
                        .until(
                                ctx -> false)
                        .build();
                return context.asSubProcess(
                        Report.class,
                        eo);
            }

        }
    }

    @Nested
    class Consumer {


        @Test
        void terminatesItselfRequiresInput() {
            AgentMetadataReader reader = new AgentMetadataReader();
            var agent = (com.embabel.agent.core.Agent) reader.createAgentMetadata(new InvalidRepeatUntilTerminatesOKConsumer());
            var ap = IntegrationTestUtils.dummyAgentPlatform();
            assertThrows(IllegalStateException.class, () -> ap.runAgentFrom(
                    agent,
                    ProcessOptions.getDEFAULT(),
                    Map.of("it", new UserInput("input"))
            ));
        }

        @Test
        void terminatesItselfGivenInputAndValidEnclosingAction() {
            AgentMetadataReader reader = new AgentMetadataReader();
            var agent = (com.embabel.agent.core.Agent) reader.createAgentMetadata(new ValidRepeatUntilTerminatesOKConsumer());
            var ap = IntegrationTestUtils.dummyAgentPlatform();
            var result = ap.runAgentFrom(
                    agent,
                    ProcessOptions.getDEFAULT(),
                    Map.of("it", new UserInput("input"), "person", new Person("John Doe", 30))
            );
            assertEquals(AgentProcessStatusCode.COMPLETED, result.getStatus());
            assertTrue(result.lastResult() instanceof Report);
            assertEquals(((Report) result.lastResult()).getContent(), "John Doe 30",
                    "Expected Person to be used as input for report creation");
        }

        @Test
        void terminatesItselfWithInput() {
            var agent = RepeatUntilBuilder
                    .returning(Report.class)
                    .withInput(Person.class)
                    .withMaxIterations(3)
                    .repeating(
                            tac -> {
                                var person = tac.last(Person.class);
                                assertNotNull(person, "Person must be provided as input");
                                return new Report(person.name + " " + person.age);
                            })
                    .until(f -> true)
                    .buildAgent("myAgent", "This is a very good agent");
            var ap = IntegrationTestUtils.dummyAgentPlatform();
            var result = ap.runAgentFrom(
                    agent,
                    ProcessOptions.getDEFAULT(),
                    Map.of("it", new UserInput("input"), "person", new Person("John Doe", 30))
            );
            assertEquals(AgentProcessStatusCode.COMPLETED, result.getStatus());
            assertTrue(result.lastResult() instanceof Report);
            assertEquals(((Report) result.lastResult()).getContent(), "John Doe 30",
                    "Expected Person to be used as input for report creation");
        }

        @Agent(description = "evaluator test")
        public static class InvalidRepeatUntilTerminatesOKConsumer {

            @AchievesGoal(description = "Creating a report")
            @Action
            public Report report(UserInput userInput, ActionContext context) {
                return RepeatUntilBuilder
                        .returning(Report.class)
                        .withInput(Person.class)
                        .withMaxIterations(3)
                        .repeating(
                                tac -> {
                                    var person = tac.last(Person.class);
                                    assertNotNull(person, "Person must be provided as input");
                                    return new Report(person.name + " " + person.age);
                                })
                        .until(f -> true)
                        .asSubProcess(context);
            }

        }

        @Agent(description = "evaluator test")
        public static class ValidRepeatUntilTerminatesOKConsumer {

            @AchievesGoal(description = "Creating a report")
            @Action
            public Report report(UserInput userInput, Person definesDependency, ActionContext context) {
                return RepeatUntilBuilder
                        .returning(Report.class)
                        .withInput(Person.class)
                        .withMaxIterations(3)
                        .repeating(
                                tac -> {
                                    var person = tac.last(Person.class);
                                    assertNotNull(person, "Person must be provided as input");
                                    return new Report(person.name + " " + person.age);
                                })
                        .until(f -> true)
                        .asSubProcess(context);
            }

        }

    }
}