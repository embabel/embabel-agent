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

import com.embabel.common.ai.classification.ClassificationRequest;
import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.classification.FailureReason;
import com.embabel.common.ai.classification.ModelProvenance;
import com.embabel.common.ai.decision.PropositionRequest;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.model.DecisionService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.exception.TypeSafeApiConnectionException;
import org.springaicommunity.typesafe.exception.TypeSafeApiException;
import org.springaicommunity.typesafe.exception.TypeSafeApiResponseValidationException;
import org.springaicommunity.typesafe.exception.TypeSafeException;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.response.ChoiceAnswer;
import org.springaicommunity.typesafe.response.SystemOneResponse;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;

/** Maps TypeSafe's native primitives into Embabel decision evidence without adding policy. */
final class TypeSafeDecisionService implements DecisionService {
    private static final Logger logger = LoggerFactory.getLogger(TypeSafeDecisionService.class);
    private static final String CLASSIFICATION_QUESTION = "classification";
    private static final String PROPOSITION_QUESTION = "proposition";
    private static final String CLASSIFICATION_INSTRUCTIONS =
            "Select the category that best describes the input.";
    private static final double UNDECIDED_PROBABILITY = 0.5d;
    private static final double DISTRIBUTION_TOLERANCE = 1.0e-6d;

    private final TypeSafeClient client;

    TypeSafeDecisionService(TypeSafeClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public String getName() {
        return client.defaultModel();
    }

    @Override
    public String getProvider() {
        return TypeSafeModelFactory.PROVIDER;
    }

    @Override
    public ClassificationResult classify(ClassificationRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            var response =
                    client.systemOne(
                            request.getInput(),
                            Map.of(CLASSIFICATION_QUESTION, choiceFor(request)));
            var answer = response.choice(CLASSIFICATION_QUESTION);
            validateDistribution(request, answer);
            var provenance = provenance(response);
            if (answer.confidence() == 0.0d) {
                return new ClassificationResult.Inconclusive(provenance);
            }
            return request.selected(answer.value(), provenance, answer.confidence());
        } catch (TypeSafeException failure) {
            return classificationFailure(failureReason(failure));
        } catch (IllegalArgumentException failure) {
            return classificationFailure(FailureReason.INVALID_RESPONSE);
        }
    }

    @Override
    public PropositionResult assess(PropositionRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            var response =
                    client.systemOne(
                            request.getInput(),
                            Map.of(PROPOSITION_QUESTION, Noul.of(request.getProposition())));
            var probability = response.noulValue(PROPOSITION_QUESTION);
            var provenance = provenance(response);
            if (probability == UNDECIDED_PROBABILITY) {
                return new PropositionResult.Inconclusive(provenance);
            }
            return new PropositionResult.Answered(
                    probability > UNDECIDED_PROBABILITY, provenance, probability);
        } catch (TypeSafeException failure) {
            return propositionFailure(failureReason(failure));
        } catch (IllegalArgumentException failure) {
            return propositionFailure(FailureReason.INVALID_RESPONSE);
        }
    }

    /** Build a native choice whose stable labels and descriptions come only from the request. */
    private static Choice choiceFor(ClassificationRequest request) {
        var choice = Choice.builder().instructions(CLASSIFICATION_INSTRUCTIONS);
        request.getCategories()
                .forEach(category -> choice.option(category.getId(), category.getDescription()));
        return choice.build();
    }

    /** Reject incomplete or unnormalised provider evidence before reducing it to a selection. */
    private static void validateDistribution(
            ClassificationRequest request, ChoiceAnswer answer) {
        var expected = new LinkedHashSet<String>();
        request.getCategories().forEach(category -> expected.add(category.getId()));
        if (!answer.probabilities().keySet().equals(expected)) {
            throw new IllegalArgumentException("TypeSafe choice support does not match the request");
        }
        var total = answer.probabilities().values().stream().mapToDouble(Double::doubleValue).sum();
        if (Math.abs(total - 1.0d) > DISTRIBUTION_TOLERANCE) {
            throw new IllegalArgumentException("TypeSafe choice probabilities are not normalized");
        }
    }

    /** Use the resolved response model when present and preserve the provider request identifier. */
    private ModelProvenance provenance(SystemOneResponse response) {
        var resolvedModel =
                response.model() == null || response.model().isBlank()
                        ? client.defaultModel()
                        : response.model();
        return new ModelProvenance(
                resolvedModel, TypeSafeModelFactory.PROVIDER, null, response.requestId());
    }

    /** Keep provider availability distinct from successful responses that fail validation. */
    private static FailureReason failureReason(TypeSafeException failure) {
        return switch (failure) {
            case TypeSafeApiResponseValidationException ignored -> FailureReason.INVALID_RESPONSE;
            case TypeSafeApiConnectionException ignored -> FailureReason.UNAVAILABLE;
            case TypeSafeApiException ignored -> FailureReason.UNAVAILABLE;
            default -> FailureReason.INVALID_RESPONSE;
        };
    }

    /** Return a bounded classification failure and emit no provider exception or payload. */
    private static ClassificationResult.Failure classificationFailure(FailureReason reason) {
        logger.warn("TypeSafe classification failed with reason {}", reason);
        return new ClassificationResult.Failure(reason);
    }

    /** Return a bounded proposition failure and emit no provider exception or payload. */
    private static PropositionResult.Failure propositionFailure(FailureReason reason) {
        logger.warn("TypeSafe proposition assessment failed with reason {}", reason);
        return new PropositionResult.Failure(reason);
    }
}
