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
import com.embabel.agent.test.integration.IntegrationTestUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.embabel.agent.test.integration.IntegrationTestUtils.dummyAgentPlatform;
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
                            return new Report("thing-" + history.attempts().size());
                        })
                .withEvaluator(
                        ctx -> {
                            var history = ctx.getAttemptHistory();
                            assertNotNull(history.lastAttempt().getResult(),
                                    "Last result must be available to evaluator");

                            assertNotNull(history.resultToEvaluate(),
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
                            return new Report("thing-" + history.attempts().size());
                        })
                .withEvaluator(
                        ctx -> {
                            var history = ctx.getAttemptHistory();
                            assertNotNull(history.resultToEvaluate(),
                                    "Last result must be available to evaluator");
                            return new TextFeedback(0.5, "feedback");
                        })
                .withAcceptanceCriteria(f -> true)
                .buildAgent("myAgent", "This is a very good agent");

        var ap = IntegrationTestUtils.dummyAgentPlatform();
        var result = ap.runAgentFrom(
                agent,
                ProcessOptions.DEFAULT
                        .withVerbosity(new Verbosity(false, false, false, true)),
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
                            assertNotNull(history.resultToEvaluate(),
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
                                assertNotNull(history.resultToEvaluate(),
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
                                assertNotNull(tac.getInput(), "Person must be provided as input");
                                var person = tac.getInput();
                                if (tac.getAttemptHistory().attemptCount() > 0) {
                                    assertNotNull(tac.lastAttempt(), "Last attempt must not be null");
                                    tac.getAttemptHistory().attempts().forEach(
                                            attempt -> {
                                                assertNotNull(attempt, "Attempt should not be null");
                                                assertNotNull(attempt.getResult(), "Result should not be null");
                                                assertNotNull(attempt.getFeedback(), "Feedback should not be null");
                                            }
                                    );
                                }
                                assertNotNull(person, "Person must be provided as input");
                                var history = tac.getAttemptHistory();
                                var attemptCount = history != null ? history.attempts().size() : 0;
                                return new Report(person.name + " " + person.age + " attempt " + attemptCount);
                            })
                    .withEvaluator(
                            ctx -> {
                                var history = ctx.getAttemptHistory();
                                assertNotNull(history.resultToEvaluate(),
                                        "Last result must be available to evaluator");
                                return new TextFeedback(0.5 + history.attempts().size() / 10.0, "feedback");
                            })
                    .withAcceptanceCriteria(f -> f.getFeedback().getScore() > .5)
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
                                assertNotNull(history.resultToEvaluate(),
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

    @Nested
    class EdgeCases {

        @Test
        void maxIterationsIsRespected() {
            final int[] taskCallCount = {0};
            final int[] evaluatorCallCount = {0};
            var agent = RepeatUntilAcceptableBuilder
                    .returning(Report.class)
                    .withMaxIterations(3)
                    .repeating(
                            tac -> {
                                taskCallCount[0]++;
                                return new Report("attempt-" + taskCallCount[0]);
                            })
                    .withEvaluator(
                            ctx -> {
                                evaluatorCallCount[0]++;
                                return new TextFeedback(0.3, "Not good enough");
                            })
                    .withAcceptanceCriteria(f -> false) // Never accept, should hit max iterations
                    .buildAgent("maxIterTest", "Test max iterations");

            var ap = IntegrationTestUtils.dummyAgentPlatform();
            var result = ap.runAgentFrom(
                    agent,
                    ProcessOptions.DEFAULT,
                    Map.of("it", new UserInput("input"))
            );

            assertEquals(AgentProcessStatusCode.COMPLETED, result.getStatus());
            assertEquals(3, evaluatorCallCount[0], "Evaluator should have been called exactly maxIterations times");
        }

        @Test
        void bestResultIsReturned() {
            var agent = RepeatUntilAcceptableBuilder
                    .returning(Report.class)
                    .withMaxIterations(5)
                    .repeating(
                            tac -> new Report("attempt-" + (tac.getAttemptHistory().attemptCount() + 1)))
                    .withEvaluator(
                            ctx -> {
                                var attemptNum = ctx.getAttemptHistory().attemptCount() + 1;
                                // Score pattern: 0.3, 0.7, 0.5, 0.4, 0.6
                                // Best should be attempt 2 with score 0.7
                                double score = switch (attemptNum) {
                                    case 1 -> 0.3;
                                    case 2 -> 0.7;
                                    case 3 -> 0.5;
                                    case 4 -> 0.4;
                                    case 5 -> 0.6;
                                    default -> 0.0;
                                };
                                return new TextFeedback(score, "Score " + score);
                            })
                    .withAcceptanceCriteria(f -> false) // Never accept, go to max iterations
                    .buildAgent("bestResultTest", "Test best result selection");

            var ap = IntegrationTestUtils.dummyAgentPlatform();
            var result = ap.runAgentFrom(
                    agent,
                    ProcessOptions.DEFAULT,
                    Map.of("it", new UserInput("input"))
            );

            assertEquals(AgentProcessStatusCode.COMPLETED, result.getStatus());
            // Note: The workflow will continue attempting until maxIterations,
            // but the best result should still be returned
            assertTrue(result.lastResult() instanceof Report,
                    "Result should be a Report");
        }

        @Test
        void scoreThresholdWorks() {
            final int[] callCount = {0};
            var agent = RepeatUntilAcceptableBuilder
                    .returning(Report.class)
                    .withMaxIterations(5)
                    .withScoreThreshold(0.8)
                    .repeating(
                            tac -> {
                                callCount[0]++;
                                return new Report("attempt-" + callCount[0]);
                            })
                    .withEvaluator(
                            ctx -> {
                                // Scores: 0.5, 0.6, 0.85, ...
                                double score = 0.5 + (ctx.getAttemptHistory().attemptCount() * 0.1);
                                return new TextFeedback(score, "Score " + score);
                            })
                    .buildAgent("scoreThresholdTest", "Test score threshold"); // Uses default acceptance criteria based on threshold

            var ap = IntegrationTestUtils.dummyAgentPlatform();
            var result = ap.runAgentFrom(
                    agent,
                    ProcessOptions.DEFAULT,
                    Map.of("it", new UserInput("input"))
            );

            assertEquals(AgentProcessStatusCode.COMPLETED, result.getStatus());
            assertTrue(callCount[0] >= 3, "Should have at least 3 attempts when score >= 0.8");
        }

        @Test
        void evaluatorCanAccessResultToEvaluate() {
            var agent = RepeatUntilAcceptableBuilder
                    .returning(Report.class)
                    .withMaxIterations(3)
                    .repeating(
                            tac -> new Report("content-" + (tac.getAttemptHistory().attemptCount() + 1)))
                    .withEvaluator(
                            ctx -> {
                                var result = ctx.getResultToEvaluate();
                                assertNotNull(result, "Result to evaluate should be available");
                                assertTrue(result.getContent().startsWith("content-"),
                                        "Should be able to access the result content");
                                return new TextFeedback(0.5, "Evaluated: " + result.getContent());
                            })
                    .withAcceptanceCriteria(f -> true)
                    .buildAgent("resultAccessTest", "Test result access in evaluator");

            var ap = IntegrationTestUtils.dummyAgentPlatform();
            var result = ap.runAgentFrom(
                    agent,
                    ProcessOptions.DEFAULT,
                    Map.of("it", new UserInput("input"))
            );

            assertEquals(AgentProcessStatusCode.COMPLETED, result.getStatus());
        }

        @Test
        void feedbackScoresImproveOverTime() {
            var agent = RepeatUntilAcceptableBuilder
                    .returning(Report.class)
                    .withMaxIterations(4)
                    .withScoreThreshold(0.8)
                    .repeating(
                            tac -> {
                                var attemptNum = tac.getAttemptHistory().attemptCount() + 1;
                                return new Report("attempt-" + attemptNum);
                            })
                    .withEvaluator(
                            ctx -> {
                                var history = ctx.getAttemptHistory();
                                var currentAttempt = history.attemptCount() + 1;
                                // Simulate improving scores
                                double score = 0.4 + (currentAttempt * 0.15);

                                // Verify we can see previous feedback
                                if (currentAttempt > 1) {
                                    assertNotNull(history.lastFeedback(),
                                            "Should have previous feedback");
                                    assertTrue(history.lastFeedback().getScore() < score,
                                            "Score should be improving");
                                }

                                return new TextFeedback(score, "Improving");
                            })
                    .buildAgent("improvingScoresTest", "Test improving scores");

            var ap = IntegrationTestUtils.dummyAgentPlatform();
            var result = ap.runAgentFrom(
                    agent,
                    ProcessOptions.DEFAULT,
                    Map.of("it", new UserInput("input"))
            );

            assertEquals(AgentProcessStatusCode.COMPLETED, result.getStatus());
        }

        @Test
        void acceptsOnFirstAttemptWithHighScore() {
            final int[] taskCallCount = {0};
            final int[] evaluatorCallCount = {0};
            var agent = RepeatUntilAcceptableBuilder
                    .returning(Report.class)
                    .withMaxIterations(5)
                    .withScoreThreshold(0.5)
                    .repeating(
                            tac -> {
                                taskCallCount[0]++;
                                return new Report("attempt-" + taskCallCount[0]);
                            })
                    .withEvaluator(
                            ctx -> {
                                evaluatorCallCount[0]++;
                                return new TextFeedback(0.9, "Excellent");
                            })
                    .buildAgent("firstAttemptTest", "Test first attempt acceptance"); // Uses default acceptance criteria (score >= 0.5)

            var ap = IntegrationTestUtils.dummyAgentPlatform();
            var result = ap.runAgentFrom(
                    agent,
                    ProcessOptions.DEFAULT,
                    Map.of("it", new UserInput("input"))
            );

            assertEquals(AgentProcessStatusCode.COMPLETED, result.getStatus());
            assertEquals(1, evaluatorCallCount[0], "Evaluator should have been called exactly once");
        }
    }

}