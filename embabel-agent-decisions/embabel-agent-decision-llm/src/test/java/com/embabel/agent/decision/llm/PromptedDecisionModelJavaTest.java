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
package com.embabel.agent.decision.llm;

import com.embabel.agent.decision.ChoiceKey;
import com.embabel.agent.decision.DecisionModel;
import com.embabel.agent.decision.DecisionOption;
import com.embabel.agent.decision.DecisionOutcome;
import com.embabel.agent.decision.DecisionRequest;
import com.embabel.agent.decision.KeyOutcome;
import com.embabel.agent.decision.RatingKey;
import com.embabel.agent.decision.YesNoKey;
import com.embabel.agent.spi.LlmService;
import com.embabel.agent.spi.loop.LlmMessageResponse;
import com.embabel.agent.spi.loop.LlmMessageSender;
import com.embabel.agent.spi.loop.streaming.LlmMessageStreamer;
import com.embabel.chat.AssistantMessage;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.common.ai.model.PricingModel;
import com.embabel.common.ai.prompt.PromptContributor;
import com.embabel.common.util.EmbabelObjectMapperHolder;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PromptedDecisionModelJavaTest {

    private static final String RESPONSE = """
            {"answers":[
              {"keyId":"yes","kind":"YES_NO","pTrue":0.75},
              {"keyId":"choice","kind":"CHOICE","probabilities":[{"supportId":"keep","probability":0.2},{"supportId":"revise","probability":0.8}]},
              {"keyId":"rating","kind":"RATING","probabilities":[{"supportId":"low","probability":0.1},{"supportId":"high","probability":0.9}]}
            ]}
            """;

    @Test
    void bothFactoryOverloadsExecuteTypedJavaDecisionsEndToEnd() {
        JavaService service = new JavaService();
        DecisionModel defaultFactory = PromptedDecisionModel.create(service, new LlmOptions());
        assertThat(defaultFactory.getName()).isEqualTo("java-model");
        assertThat(defaultFactory.getProvider()).isEqualTo("prompted");
        assertTypedResult(defaultFactory);
        assertTypedResult(PromptedDecisionModel.create(
                service,
                new LlmOptions(),
                EmbabelObjectMapperHolder.createDefault()
        ));
        assertThat(service.calls).isEqualTo(2);
    }

    @Test
    void javacAcceptsThePublicTypedConsumerWithoutUncheckedWarnings() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).isNotNull();
        Path directory = Files.createTempDirectory("prompted-decision-java-");
        Path source = directory.resolve("Consumer.java");
        Files.writeString(source, """
                import com.embabel.agent.decision.*;
                class Consumer {
                  void check(DecisionOutcome.Success result, YesNoKey yes, ChoiceKey<String> choice, RatingKey<Integer> rating) {
                    KeyOutcome<Boolean> a = result.answer(yes);
                    KeyOutcome<String> b = result.answer(choice);
                    KeyOutcome<Integer> c = result.answer(rating);
                  }
                }
                """);

        int exit = compiler.run(null, null, null,
                "-Xlint:unchecked", "-Werror",
                "-classpath", System.getProperty("java.class.path"),
                source.toString());
        assertThat(exit).isZero();
    }

    private static void assertTypedResult(DecisionModel model) {
        DecisionRequest.Builder builder = DecisionRequest.builder();
        YesNoKey yes = builder.yesNo("yes", "Is revision warranted?");
        ChoiceKey<String> choice = builder.choice("choice", "Which proposition wins?", List.of(
                DecisionOption.of("keep", "keep-value", "Keep"),
                DecisionOption.of("revise", "revise-value", "Revise")
        ));
        RatingKey<Integer> rating = builder.rating("rating", "How strong is the evidence?", List.of(
                DecisionOption.of("low", 1, "Low"),
                DecisionOption.of("high", 2, "High")
        ));

        DecisionOutcome outcome = model.ask(builder.build());
        assertThat(outcome).isInstanceOf(DecisionOutcome.Success.class);
        DecisionOutcome.Success success = (DecisionOutcome.Success) outcome;
        KeyOutcome<Boolean> yesOutcome = success.answer(yes);
        KeyOutcome<String> choiceOutcome = success.answer(choice);
        KeyOutcome<Integer> ratingOutcome = success.answer(rating);
        assertThat(yesOutcome).isInstanceOf(KeyOutcome.Success.class);
        assertThat(choiceOutcome).isInstanceOf(KeyOutcome.Success.class);
        assertThat(ratingOutcome).isInstanceOf(KeyOutcome.Success.class);
        assertThat(((KeyOutcome.Success<?>) yesOutcome).getValue()).isEqualTo(true);
        assertThat(((KeyOutcome.Success<?>) choiceOutcome).getValue()).isEqualTo("revise-value");
        assertThat(((KeyOutcome.Success<?>) ratingOutcome).getValue()).isEqualTo(2);
    }

    private static final class JavaService implements LlmService<JavaService> {
        private int calls;

        @Override
        public LlmMessageSender createMessageSender(LlmOptions options) {
            return (messages, tools) -> {
                calls++;
                assertThat(tools).isEmpty();
                return new LlmMessageResponse(new AssistantMessage(RESPONSE), RESPONSE, null);
            };
        }

        @Override
        public LlmMessageStreamer createMessageStreamer(LlmOptions options) {
            throw new UnsupportedOperationException("streaming is not used");
        }

        @Override
        public boolean supportsStreaming() {
            return false;
        }

        @Override
        public String getName() {
            return "java-model";
        }

        @Override
        public String getProvider() {
            return "java-provider";
        }

        @Override
        public LocalDate getKnowledgeCutoffDate() {
            return null;
        }

        @Override
        public PricingModel getPricingModel() {
            return null;
        }

        @Override
        public List<PromptContributor> getPromptContributors() {
            return List.of();
        }

        @Override
        public JavaService withKnowledgeCutoffDate(LocalDate date) {
            return this;
        }

        @Override
        public JavaService withPromptContributor(PromptContributor promptContributor) {
            return this;
        }
    }

}
