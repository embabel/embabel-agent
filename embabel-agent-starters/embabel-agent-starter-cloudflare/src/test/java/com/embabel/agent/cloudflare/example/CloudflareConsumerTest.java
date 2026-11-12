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
package com.embabel.agent.cloudflare.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.decision.ChoiceQuestionSpec;
import com.embabel.common.ai.decision.DecisionResponse;
import com.embabel.common.ai.decision.DecisionService;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.Questions;
import com.embabel.common.ai.decision.RatingQuestionSpec;
import com.embabel.common.ai.decision.RatingResult;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class CloudflareConsumerTest {

    private static final String ACCOUNT = "0123456789abcdef0123456789abcdef";

    @Test
    void aSpringConsumerAsksClefFlashThroughTheDecisionContracts() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(
                        requestTo(
                                "https://api.cloudflare.com/client/v4/accounts/"
                                        + ACCOUNT
                                        + "/ai/run/@cf/cloudflare/clef-flash"))
                .andRespond(
                        withSuccess(
                                """
                                {"success":true,"errors":[],"messages":[],"result":{"model":"clef-flash","answers":{
                                  "q1":{"type":"choice","choice":"returned","probabilities":{"delivered":0.25,"returned":0.75},"confidence":0.5},
                                  "q2":{"type":"score","score":0.75,"legend":{"0":"Low","1":"High"},"probabilities":{"0":0.25,"1":0.75},"confidence":0.2}}}}
                                """,
                                MediaType.APPLICATION_JSON));

        new ApplicationContextRunner()
                .withUserConfiguration(ConsumerConfiguration.class)
                .withBean(RestClient.Builder.class, () -> builder)
                .withPropertyValues(
                        "CLOUDFLARE_API_TOKEN=",
                        "CLOUDFLARE_ACCOUNT_ID=",
                        "embabel.agent.platform.models.cloudflare.api-key=test-token",
                        "embabel.agent.platform.models.cloudflare.account-id=" + ACCOUNT)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var response =
                                    context.getBean(ParcelReview.class).review("The parcel came back unopened.");
                            assertThat(response.answer(ParcelReview.STATUS))
                                    .isInstanceOfSatisfying(
                                            ClassificationResult.Selected.class,
                                            selected -> assertThat(selected.getCategoryId()).isEqualTo("returned"));
                            assertThat(response.answer(ParcelReview.RISK)).isInstanceOf(RatingResult.Answered.class);
                        });
        server.verify();
    }

    // tag::cloudflare-consumer[]
    public record ParcelReview(DecisionService clefFlash) {

        static final ChoiceQuestionSpec STATUS =
                Questions.named("status")
                        .choice("What happened to the parcel?")
                        .option("delivered", "It reached the customer")
                        .option("returned", "It came back to the sender")
                        .build();

        static final RatingQuestionSpec RISK =
                Questions.named("risk")
                        .rating("How likely is a refund claim?")
                        .level("low", "Low")
                        .level("high", "High")
                        .build();

        public DecisionResponse review(String message) {
            return clefFlash.ask(message, DecisionSpec.of(STATUS, RISK));
        }
    }
    // end::cloudflare-consumer[]

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class ConsumerConfiguration {
        // tag::cloudflare-qualifier[]
        @Bean
        ParcelReview parcelReview(@Qualifier("clef-flash") DecisionService clefFlash) {
            return new ParcelReview(clefFlash);
        }
        // end::cloudflare-qualifier[]
    }
}
