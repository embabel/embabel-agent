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
import com.embabel.agent.api.common.TransformationActionContext;
import com.embabel.agent.core.AgentProcessStatusCode;
import com.embabel.agent.core.ProcessOptions;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.testing.integration.IntegrationTestUtils;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluatorOptimizerJavaTest {

    @Test
    void doesNotTerminate() {
        doesNotTerminate(new EvaluationFlowDoesNotTerminateJava());
    }

    @Test
    void doesNotTerminateBuilder() {
        doesNotTerminate(new EvaluationBuilderFlowDoesNotTerminateJava());
    }

    @Test
    void doesNotTerminateClass() {
        var f = new EvaluationBuilderFlowDoesNotTerminateClass(Report.class, 3);
        doesNotTerminate(Builders.createAgent(
                f, "name", "description", "version"
        ));
    }


    private void doesNotTerminate(Object ag) {
        AgentMetadataReader reader = new AgentMetadataReader();
        var agent = (com.embabel.agent.core.Agent) reader.createAgentMetadata(ag);
        var ap = IntegrationTestUtils.dummyAgentPlatform();
        var result = ap.runAgentFrom(
                agent,
                ProcessOptions.Companion.getDEFAULT(),
                Map.of("it", new UserInput("input"))
        );
        assertEquals(AgentProcessStatusCode.COMPLETED, result.getStatus());
        assertTrue(result.lastResult() instanceof Report);
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
    public static class EvaluationFlowDoesNotTerminateJava {
        @Action
        public ScoredResult<Report, SimpleFeedback> toFeedback(UserInput userInput, ActionContext context) {
            final int[] count = {0};
            var eo = EvaluatorOptimizer.generateUntilAcceptable(
                    (tac) -> {
                        count[0]++;
                        return new Report("thing-" + count[0]);
                    },
                    (ctx) -> new SimpleFeedback(0.5, "feedback"),
                    (f) -> false, // never acceptable, hit max iterations
                    3,
                    Report.class,
                    SimpleFeedback.class
            );
            return context.asSubProcess(
                    (Class<ScoredResult<Report, SimpleFeedback>>) (Class<?>) ScoredResult.class,
                    eo
            );
        }

        @AchievesGoal(description = "Creating a person")
        @Action
        public Report done(ScoredResult<Report, SimpleFeedback> scoredResult) {
            assertEquals(0.5, scoredResult.getFeedback().getScore());
            return scoredResult.getResult();
        }
    }

    @Agent(description = "evaluator test")
    public static class EvaluationBuilderFlowDoesNotTerminateJava {
        @Action
        public ScoredResult<Report, SimpleFeedback> toFeedback(UserInput userInput, ActionContext context) {
            final int[] count = {0};
            var eo = new EvaluatorOptimizerBuilder<Report, SimpleFeedback>(Report.class, SimpleFeedback.class)
                    .withGenerator(
                            tac -> {
                                count[0]++;
                                return new Report("thing-" + count[0]);
                            }).withEvaluator(
                            ctx -> new SimpleFeedback(0.5, "feedback"))
                    .withAcceptanceCriteria(
                            f -> false)// never acceptable, hit max iterations
                    .withMaxIterations(3)
                    .build();

            return context.asSubProcess(
                    (Class<ScoredResult<Report, SimpleFeedback>>) (Class<?>) ScoredResult.class,
                    eo
            );
        }

        @AchievesGoal(description = "Creating a person")
        @Action
        public Report done(ScoredResult<Report, SimpleFeedback> scoredResult) {
            assertEquals(0.5, scoredResult.getFeedback().getScore());
            return scoredResult.getResult();
        }
    }

    public record EvaluationBuilderFlowDoesNotTerminateClass(
            Class<Report> resultClass,
            int maxIterations
    ) implements EvaluatorOptimizerFlow<Report, SimpleFeedback> {

        @Override
        @NotNull
        public Class<SimpleFeedback> feedbackClass() {
            return SimpleFeedback.class;
        }

        @Override
        @NotNull
        public Report generate(@NotNull TransformationActionContext<SimpleFeedback, Report> context) {
            return new Report("thing-" + UUID.randomUUID());
        }

        @Override
        @NotNull
        public SimpleFeedback evaluate(@NotNull TransformationActionContext<Report, SimpleFeedback> context) {
            return new SimpleFeedback(0.5, "feedback");
        }

        @Override
        public boolean judge(@NotNull SimpleFeedback feedback) {
            return false;
        }

    }

}
