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
package com.embabel.agent.typesafe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

import com.embabel.common.ai.classification.Category;
import com.embabel.common.ai.classification.ClassificationRequest;
import com.embabel.common.ai.classification.ClassificationSpec;
import com.embabel.common.ai.decision.DecisionRequest;
import com.embabel.common.ai.decision.PropositionRequest;
import com.embabel.common.ai.decision.Questions;
import com.embabel.common.ai.model.DecisionService;

import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Interrupted provider calls rethrow an InterruptedException with the interrupt flag set. */
class TypeSafeInterruptionTest {
    private static final String SYSTEM_ONE_URI = "https://api.typesafe.ai/v1/systemone";

    private final AtomicInteger calls = new AtomicInteger();
    private final DecisionService service = interruptingService();

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void interruptedNativeAskRethrows() {
        var request =
                DecisionRequest.of(
                        "An email.", Questions.named("urgent").proposition("Is this urgent?").build());

        assertThatThrownBy(() -> service.ask(request))
                .isInstanceOf(InterruptedException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void interruptedClassifyRethrows() {
        var request =
                ClassificationRequest.of("An email.", ClassificationSpec.builder().asking("Which category fits?").category("a", "A").category("b", "B").build());

        assertThatThrownBy(() -> service.classify(request)).isInstanceOf(InterruptedException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void interruptedAssessRethrows() {
        assertThatThrownBy(() -> service.assess(new PropositionRequest("An email.", "Is this urgent?")))
                .isInstanceOf(InterruptedException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    // Each request interrupts the calling thread and fails the way an interrupted blocking read does.
    private DecisionService interruptingService() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(manyTimes(), requestTo(SYSTEM_ONE_URI))
                .andRespond(
                        request -> {
                            calls.incrementAndGet();
                            Thread.currentThread().interrupt();
                            throw new IOException("interrupted read");
                        });
        return new TypeSafeModelFactory(
                        TypeSafeClientOptions.defaults(),
                        () -> "key",
                        builder,
                        ObservationRegistry.NOOP,
                        TypeSafeModelFactory.DEFAULT_MODEL)
                .build();
    }
}
