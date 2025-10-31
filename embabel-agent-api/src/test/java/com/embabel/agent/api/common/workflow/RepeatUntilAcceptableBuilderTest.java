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
import com.embabel.agent.api.common.workflow.loop.TextFeedback;
import com.embabel.agent.core.AgentProcessStatusCode;
import com.embabel.agent.core.ProcessOptions;
import com.embabel.agent.core.Verbosity;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.testing.integration.IntegrationTestUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.embabel.agent.testing.integration.IntegrationTestUtils.dummyAgentPlatform;
import static org.junit.jupiter.api.Assertions.*;

class RepeatUntilAcceptableBuilderTest {

    record Person(String name, int age) {
    }

    @Test
    void testNoExportedActionsFromWorkflow() {
        var agent = RepeatUntilAcceptableBuilder
                .returning(Report.class)
                .withMaxIterations(3)
                .repeating(
                        tac -> {
                            var history = tac.getAttemptHistory();
                            return new Report("thing-" + (history != null ? history.attempts().size() : 0));
                        })
                .withEvaluator(
                        ctx -> {
                            var history = ctx.getAttemptHistory();
                            assertNotNull(history != null ? history.resultToEvaluate() : null,
                                    "Last result must be available to evaluator");
                            return new TextFeedback(0.5, "feedback");
                        })
                .withAcceptanceCriteria(f -> true)
                .buildAgent("myAgent", "This is a very good agent");

        assertFalse(agent.getActions().isEmpty(), "Should have actions");
        var ap = dummyAgentPlatform();
        ap.deploy(agent);
        assertTrue(agent.getOpaque(), "Agent should be opaque");

        assertTrue(agent.getActions().size() > 1,
                "Should have actions on the agent");
        assertEquals(0, ap.getActions().size());
    }

    @Test
    void terminatesItself() {
        AgentMetadataReader reader = new AgentMetadataReader();
        var agent = (com.embabel.agent.core.Agent) reader.createAgentMetadata(new EvaluationFlowTerminatesOkJava());
        var ap = IntegrationTestUtils.dummyAgentPlatform();
        var result = ap.runAgentFrom(
                agent,
                ProcessOptions.DEFAULT,
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
    void terminatesItselfSimple() {
        AgentMetadataReader reader = new AgentMetadataReader();
        var agent = (com.embabel.agent.core.Agent) reader.createAgentMetadata(new EvaluationFlowTerminatesOkSimpleJava());
        var ap = IntegrationTestUtils.dummyAgentPlatform();
        var result = ap.runAgentFrom(
                agent,
                ProcessOptions.DEFAULT,
                Map.of("it", new UserInput("input"))
        );
        assertEquals(AgentProcessStatusCode.COMPLETED, result.getStatus());
        assertTrue(result.lastResult() instanceof Report);
    }

    @Test
    void doesNotTerminateItself() {
        AgentMetadataReader reader = new AgentMetadataReader();
        var agent = (com.embabel.agent.core.Agent) reader.createAgentMetadata(new EvaluationFlowDoesNotTerminateJava());
        var ap = IntegrationTestUtils.dummyAgentPlatform();
        var result = ap.runAgentFrom(
                agent,
                ProcessOptions.DEFAULT,
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
                            var history = tac.getAttemptHistory();
                            return new Report("thing-" + (history != null ? history.attempts().size() : 0));
                        })
                .withEvaluator(
                        ctx -> {
                            var history = ctx.getAttemptHistory();
                            assertNotNull(history != null ? history.resultToEvaluate() : null,
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

    @Nested
    class Consumer {

        com.embabel.agent.core.Agent takesPerson = RepeatUntilAcceptableBuilder
                .returning(Report.class)
                .consuming(Person.class)
                .withMaxIterations(3)
                .repeating(
                        tac -> {
                            var person = tac.getInput();
                            assertNotNull(person, "Person must be provided as input");
                            var history = tac.getAttemptHistory();
                            var attemptCount = history != null ? history.attempts().size() : 0;
                            return new Report(person.name + " " + person.age + " attempt " + attemptCount);
                        })
                .withEvaluator(
                        ctx -> {
                            var person = ctx.getInput();
                            assertNotNull(person, "Person must be provided as input");
                            var history = ctx.getAttemptHistory();
                            assertNotNull(history != null ? history.resultToEvaluate() : null,
                                    "Last result must be available to evaluator");
                            return new TextFeedback(0.5, "feedback for " + person.name);
                        })
                .withAcceptanceCriteria(f -> true)
                .buildAgent("takesPerson", "Takes a person as input");

        @Test
        void testWithConsumingBuildAgent() {
            var agent = RepeatUntilAcceptableBuilder
                    .returning(Report.class)
                    .consuming(Person.class)
                    .withMaxIterations(3)
                    .repeating(
                            tac -> {
                                var person = tac.getInput();
                                assertNotNull(person, "Person must be provided as input");
                                return new Report(person.name + " " + person.age);
                            })
                    .withEvaluator(
                            ctx -> {
                                var history = ctx.getAttemptHistory();
                                assertNotNull(history != null ? history.resultToEvaluate() : null,
                                        "Last result must be available to evaluator");
                                return new TextFeedback(0.5, "feedback");
                            })
                    .withAcceptanceCriteria(f -> true)
                    .buildAgent("myAgent", "This is a very good agent");

            assertFalse(agent.getActions().isEmpty(), "Should have actions");
            var ap = dummyAgentPlatform();
            ap.deploy(agent);
            assertTrue(agent.getOpaque(), "Agent should be opaque");

            assertTrue(agent.getActions().size() > 1,
                    "Should have actions on the agent");
            assertEquals(0, ap.getActions().size());
        }

        @Test
        void testWithConsumingRunAgent() {
            var agent = RepeatUntilAcceptableBuilder
                    .returning(Report.class)
                    .consuming(Person.class)
                    .withMaxIterations(3)
                    .repeating(
                            tac -> {
                                var person = tac.getInput();
                                assertNotNull(person, "Person must be provided as input");
                                var history = tac.getAttemptHistory();
                                var attemptCount = history != null ? history.attempts().size() : 0;
                                return new Report(person.name + " " + person.age + " attempt " + attemptCount);
                            })
                    .withEvaluator(
                            ctx -> {
                                var history = ctx.getAttemptHistory();
                                assertNotNull(history != null ? history.resultToEvaluate() : null,
                                        "Last result must be available to evaluator");
                                return new TextFeedback(0.5, "feedback");
                            })
                    .withAcceptanceCriteria(f -> true)
                    .buildAgent("myAgent", "This is a very good agent");

            var ap = IntegrationTestUtils.dummyAgentPlatform();
            var result = ap.runAgentFrom(
                    agent,
                    ProcessOptions.DEFAULT,
                    Map.of("it", new Person("Alice", 30))
            );
            assertEquals(AgentProcessStatusCode.COMPLETED, result.getStatus());
            assertTrue(result.lastResult() instanceof Report);
            var report = (Report) result.lastResult();
            assertTrue(report.getContent().contains("Alice"),
                    "Report should contain person name: " + report.getContent());
            assertTrue(report.getContent().contains("30"),
                    "Report should contain person age: " + report.getContent());
        }

        @Test
        void testAsSubProcess() {
            AgentMetadataReader reader = new AgentMetadataReader();
            var agent = (com.embabel.agent.core.Agent) reader.createAgentMetadata(new ConsumingSubProcessAgent());
            var ap = IntegrationTestUtils.dummyAgentPlatform();
            var result = ap.runAgentFrom(
                    agent,
                    ProcessOptions.DEFAULT,
                    Map.of("it", new UserInput("input"), "person", new Person("Bob", 25))
            );
            assertEquals(AgentProcessStatusCode.COMPLETED, result.getStatus());
            assertTrue(result.lastResult() instanceof Report);
            var report = (Report) result.lastResult();
            assertTrue(report.getContent().contains("Bob"),
                    "Report should contain person name: " + report.getContent());
        }
    }

    @Agent(description = "consumer test")
    public static class ConsumingSubProcessAgent {

        @AchievesGoal(description = "Creating a report")
        @Action
        public Report report(UserInput userInput, Person person, ActionContext context) {
            var eo = RepeatUntilAcceptableBuilder
                    .returning(Report.class)
                    .consuming(Person.class)
                    .withMaxIterations(3)
                    .repeating(
                            tac -> {
                                var p = tac.getInput();
                                assertNotNull(p, "Person must be provided as input");
                                var history = tac.getAttemptHistory();
                                var attemptCount = history != null ? history.attempts().size() : 0;
                                return new Report(p.name + " " + p.age + " attempt " + attemptCount);
                            })
                    .withEvaluator(
                            ctx -> {
                                var history = ctx.getAttemptHistory();
                                assertNotNull(history != null ? history.resultToEvaluate() : null,
                                        "Last result must be available to evaluator");
                                return new TextFeedback(0.5, "feedback");
                            })
                    .withAcceptanceCriteria(f -> true)
                    .build();
            return context.asSubProcess(
                    Report.class,
                    eo);
        }
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

    @Agent(description = "evaluator test")
    public static class EvaluationFlowTerminatesOkJava {

        @AchievesGoal(description = "Creating a report")
        @Action
        public Report report(UserInput userInput, ActionContext context) {
            final int[] count = {0};
            var eo = RepeatUntilAcceptableBuilder
                    .returning(Report.class)
                    .withMaxIterations(3)
                    .repeating(
                            tac -> {
                                count[0]++;
                                return new Report("thing-" + count[0]);
                            })
                    .withEvaluator(
                            ctx -> new TextFeedback(0.5, "feedback"))
                    .withAcceptanceCriteria(f -> true)
                    .build();
            return context.asSubProcess(
                    Report.class,
                    eo);
        }

    }

    @Agent(description = "evaluator test")
    public static class EvaluationFlowDoesNotTerminateJava {

        @AchievesGoal(description = "Creating a report")
        @Action
        public Report report(UserInput userInput, ActionContext context) {
            final int[] count = {0};
            var eo = RepeatUntilAcceptableBuilder
                    .returning(Report.class)
                    .withFeedbackClass(TextFeedback.class)
                    .withMaxIterations(3)
                    .repeating(
                            tac -> {
                                count[0]++;
                                return new Report("thing-" + count[0]);
                            })
                    .withEvaluator(
                            ctx -> new TextFeedback(0.5, "feedback"))
                    .withAcceptanceCriteria(f -> false) // never acceptable, hit max iterations
                    .build();
            return context.asSubProcess(
                    Report.class,
                    eo);
        }

    }

    @Agent(description = "evaluator test")
    public static class EvaluationFlowTerminatesOkSimpleJava {

        @AchievesGoal(description = "Creating a report")
        @Action
        public Report report(UserInput userInput, ActionContext context) {
            final int[] count = {0};
            var eo = RepeatUntilAcceptableBuilder
                    .returning(Report.class)
                    .withMaxIterations(3)
                    .withScoreThreshold(.4)
                    .repeating(
                            tac -> {
                                count[0]++;
                                return new Report("thing-" + count[0]);
                            })
                    .withEvaluator(
                            ctx -> new TextFeedback(0.5, "feedback"))
                    .build();
            return context.asSubProcess(
                    Report.class,
                    eo);
        }

    }

}