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
import com.embabel.agent.decision.DecisionKey;
import com.embabel.agent.decision.DecisionModel;
import com.embabel.agent.decision.DecisionOption;
import com.embabel.agent.decision.DecisionRequest;
import com.embabel.agent.decision.KeyOutcome;
import com.embabel.agent.decision.RatingKey;
import com.embabel.agent.decision.YesNoKey;
import com.embabel.common.ai.model.LlmOptions;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PromptedDecisionModelJavaTest {

    @Test
    void factoryHasBothJavaOverloads() throws Exception {
        Method withMapper = PromptedDecisionModel.class.getMethod(
                "create",
                com.embabel.agent.spi.LlmService.class,
                LlmOptions.class,
                com.embabel.common.util.EmbabelObjectMapperHolder.class
        );
        Method withDefaults = PromptedDecisionModel.class.getMethod(
                "create",
                com.embabel.agent.spi.LlmService.class,
                LlmOptions.class
        );

        assertThat(withMapper.getReturnType()).isEqualTo(DecisionModel.class);
        assertThat(withDefaults.getReturnType()).isEqualTo(DecisionModel.class);
    }

    @Test
    void javaTypedKeysNeedNoCast() {
        DecisionRequest.Builder builder = DecisionRequest.builder();
        YesNoKey yesNo = builder.yesNo("yes", "Is the proposition revision warranted?");
        ChoiceKey<String> choice = builder.choice("choice", "Which proposition wins?", List.of(
                DecisionOption.of("keep", "keep", "Keep"),
                DecisionOption.of("revise", "revise", "Revise")
        ));
        RatingKey<Integer> rating = builder.rating("rating", "How strong is the evidence?", List.of(
                DecisionOption.of("low", 1, "Low"),
                DecisionOption.of("high", 2, "High")
        ));

        KeyOutcome<Boolean> yesNoOutcome = typeCheck(null, yesNo);
        KeyOutcome<String> choiceOutcome = typeCheck(null, choice);
        KeyOutcome<Integer> ratingOutcome = typeCheck(null, rating);
        assertThat(builder.build()).isNotNull();
        assertThat(yesNoOutcome).isNull();
        assertThat(choiceOutcome).isNull();
        assertThat(ratingOutcome).isNull();
    }

    @Test
    void javacAcceptsThePublicTypedConsumer() throws Exception {
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

    private static <T> KeyOutcome<T> typeCheck(
            com.embabel.agent.decision.DecisionOutcome.Success outcome,
            DecisionKey<T> key
    ) {
        return outcome == null ? null : outcome.answer(key);
    }
}
