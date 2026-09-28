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
import com.embabel.common.ai.decision.DecisionCapabilities;
import com.embabel.common.ai.decision.DecisionRequest;
import com.embabel.common.ai.decision.DecisionResponse;
import com.embabel.common.ai.decision.DecisionService;
import com.embabel.common.ai.decision.PropositionQuestionSpec;
import com.embabel.common.ai.decision.PropositionRequest;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.decision.QuestionKind;
import com.embabel.common.ai.decision.RatingQuestionSpec;
import com.embabel.common.ai.decision.RatingResult;
import com.embabel.common.ai.decision.spi.DecisionContentCapture;
import com.embabel.common.ai.decision.spi.NativeQuestionSetExecution;
import com.embabel.common.ai.decision.spi.PropositionAssessment;
import com.embabel.common.ai.decision.spi.RatingAssessment;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.exception.TypeSafeApiConnectionException;
import org.springaicommunity.typesafe.exception.TypeSafeApiException;
import org.springaicommunity.typesafe.exception.TypeSafeApiResponseValidationException;
import org.springaicommunity.typesafe.exception.TypeSafeApiTimeoutException;
import org.springaicommunity.typesafe.exception.TypeSafeException;
import org.springaicommunity.typesafe.exception.TypeSafeRateLimitException;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.Question;
import org.springaicommunity.typesafe.response.ChoiceAnswer;
import org.springaicommunity.typesafe.response.SystemOneResponse;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/**
 * Maps TypeSafe's native primitives into Embabel decision evidence without adding policy.
 *
 * <p>A whole decision spec runs as one {@code systemOne} call. Proposition and rating questions
 * asked on their own run as a one-question call each. A choice question asked on its own goes
 * through {@code classify}, with the question's instructions and categories. Every failed provider call logs one
 * WARN line with bounded fields: service, provider, operation, reason, cause category, HTTP status class,
 * attempts, elapsed time, exception class and provider request id. Exception messages, bodies,
 * headers and endpoints are left out, because they can echo the input or carry credentials.
 *
 * <p>A failed provider call made while the thread is interrupted, or failing with an
 * {@link InterruptedException} or {@link ClosedByInterruptException} in its cause chain, throws an
 * unchecked {@link CancellationException} with the interrupt flag set, so callers never handle a
 * checked exception. Its cause is the original {@link InterruptedException} when the chain holds
 * one, and otherwise a new one caused by the failure.
 */
final class TypeSafeDecisionService
        implements DecisionService,
                NativeQuestionSetExecution,
                PropositionAssessment,
                RatingAssessment {
    private static final Logger logger = LoggerFactory.getLogger(TypeSafeDecisionService.class);
    private static final String CLASSIFICATION_QUESTION = "classification";
    private static final String PROPOSITION_QUESTION = "proposition";
    private static final String PROPOSITION_LABEL = "proposition assessment";
    private static final String QUESTION = "question";
    private static final String CLASSIFY_OPERATION = "classify";
    private static final String ASSESS_OPERATION = "assess";
    private static final String REQUEST_ARGUMENT = "request";
    private static final String INVALID_RESPONSE_CAUSE = "invalid_response";
    private static final double UNDECIDED_PROBABILITY = 0.5d;
    private static final double DISTRIBUTION_TOLERANCE = 1.0e-6d;

    // The SDK declares no question or input limits, so none are reported.
    private static final DecisionCapabilities CAPABILITIES =
            DecisionCapabilities.of(EnumSet.allOf(QuestionKind.class));

    // The factory builds every client with RetryPolicy.noRetry(), so each call is one attempt.
    private static final int ATTEMPTS = 1;

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
    public DecisionCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public DecisionResponse askNative(DecisionRequest request) {
        Objects.requireNonNull(request, REQUEST_ARGUMENT);
        return runQuestionSet(request, "ask_native", "question set");
    }

    @Override
    public RatingResult rate(String input, RatingQuestionSpec question) {
        Objects.requireNonNull(question, QUESTION);
        return runQuestionSet(DecisionRequest.of(input, question), "rate", "rating")
                .answer(question);
    }

    /** Assesses one proposition question through the proposition call, with the question's instructions. */
    @Override
    public PropositionResult assess(String input, PropositionQuestionSpec question) {
        Objects.requireNonNull(question, QUESTION);
        return assess(new PropositionRequest(input, question.getInstructions()));
    }

    @Override
    public ClassificationResult classify(ClassificationRequest request) {
        Objects.requireNonNull(request, REQUEST_ARGUMENT);
        var started = System.nanoTime();
        try {
            var response =
                    systemOne(request.getInput(), Map.of(CLASSIFICATION_QUESTION, choiceFor(request)));
            traceResponse(CLASSIFY_OPERATION, response);
            var answer = response.choice(CLASSIFICATION_QUESTION);
            validateDistribution(request, answer);
            var provenance = provenance(response);
            if (answer.confidence() == 0.0d) {
                return new ClassificationResult.Inconclusive(provenance);
            }
            return request.getSpec().selected(answer.value(), provenance, answer.confidence());
        } catch (TypeSafeException failure) {
            rethrowIfInterrupted(failure, CLASSIFY_OPERATION);
            var reason = failureReason(failure);
            logFailure(CLASSIFICATION_QUESTION, CLASSIFY_OPERATION, reason, failure, started);
            return new ClassificationResult.Failure(reason);
        } catch (IllegalArgumentException failure) {
            // Validation errors can contain response data; log only bounded fields.
            logFailure(
                    CLASSIFICATION_QUESTION,
                    CLASSIFY_OPERATION,
                    FailureReason.INVALID_RESPONSE,
                    failure,
                    started);
            return new ClassificationResult.Failure(FailureReason.INVALID_RESPONSE);
        }
    }

    @Override
    public PropositionResult assess(PropositionRequest request) {
        Objects.requireNonNull(request, REQUEST_ARGUMENT);
        var started = System.nanoTime();
        try {
            var response =
                    systemOne(
                            request.getInput(),
                            Map.of(PROPOSITION_QUESTION, Noul.of(request.getProposition())));
            traceResponse(ASSESS_OPERATION, response);
            var probability = response.noulValue(PROPOSITION_QUESTION);
            var provenance = provenance(response);
            if (probability == UNDECIDED_PROBABILITY) {
                return new PropositionResult.Inconclusive(provenance);
            }
            return new PropositionResult.Answered(
                    probability > UNDECIDED_PROBABILITY, provenance, probability);
        } catch (TypeSafeException failure) {
            rethrowIfInterrupted(failure, ASSESS_OPERATION);
            var reason = failureReason(failure);
            logFailure(PROPOSITION_LABEL, ASSESS_OPERATION, reason, failure, started);
            return new PropositionResult.Failure(reason);
        } catch (IllegalArgumentException failure) {
            // Validation errors can contain response data; log only bounded fields.
            logFailure(
                    PROPOSITION_LABEL,
                    ASSESS_OPERATION,
                    FailureReason.INVALID_RESPONSE,
                    failure,
                    started);
            return new PropositionResult.Failure(FailureReason.INVALID_RESPONSE);
        }
    }

    /**
     * Sends every question of the request in one call and maps the answers onto the spec. A failed
     * call fails the whole request with its reason.
     *
     * @param request the input and the questions to answer
     * @param operation the operation name for logging
     * @param label the label for the failure log line
     * @return the response for the request's spec
     */
    private DecisionResponse runQuestionSet(
            DecisionRequest request, String operation, String label) {
        var spec = request.getSpec();
        var started = System.nanoTime();
        try {
            var response = systemOne(request.getInput(), TypeSafeQuestionSets.questions(spec));
            traceResponse(operation, response);
            return TypeSafeQuestionSets.response(spec, response, provenance(response), getName());
        } catch (TypeSafeException failure) {
            rethrowIfInterrupted(failure, operation);
            var reason = failureReason(failure);
            logFailure(label, operation, reason, failure, started);
            return DecisionResponse.failed(spec, reason);
        } catch (IllegalArgumentException failure) {
            // Mapping errors can contain response data; log only bounded fields.
            logFailure(label, operation, FailureReason.INVALID_RESPONSE, failure, started);
            return DecisionResponse.failed(spec, FailureReason.INVALID_RESPONSE);
        }
    }

    /**
     * Sends one {@code systemOne} call. A missing response throws an
     * {@link IllegalArgumentException}, which the caller reports as an invalid response.
     *
     * @param input the text the model reasons over
     * @param questions the native questions, keyed by question key
     * @return the provider's response
     */
    private SystemOneResponse systemOne(String input, Map<String, ? extends Question> questions) {
        @Nullable SystemOneResponse response = client.systemOne(input, questions);
        if (response == null) {
            throw new IllegalArgumentException("TypeSafe returned no response");
        }
        return response;
    }

    /**
     * Builds a native choice whose instructions, labels and descriptions come only from the request.
     *
     * @param request the classification request
     * @return the choice to send
     */
    private static Choice choiceFor(ClassificationRequest request) {
        var choice = Choice.builder().instructions(request.getInstructions());
        request.getCategories()
                .forEach(category -> choice.option(category.getId(), category.getDescription()));
        return choice.build();
    }

    /**
     * Rejects provider evidence that is incomplete or does not sum to one, before it becomes a selection.
     *
     * @param request the classification request
     * @param answer the provider's choice answer
     */
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

    /**
     * Builds the provenance from the response, using the resolved model when there is one and keeping the request id.
     *
     * @param response the provider response
     * @return the provenance
     */
    private ModelProvenance provenance(SystemOneResponse response) {
        var resolvedModel =
                response.model() == null || response.model().isBlank()
                        ? client.defaultModel()
                        : response.model();
        return new ModelProvenance(
                resolvedModel, TypeSafeModelFactory.PROVIDER, null, response.requestId());
    }

    /**
     * Throws a {@link CancellationException} caused by an {@link InterruptedException} when a failed
     * provider call was interrupted. The SDK
     * reports an interrupted transport read as a {@link TypeSafeException} and restores the flag,
     * so a set flag counts as an interruption along with an interruption in the cause chain. The
     * flag stays set.
     *
     * @param failure the failed provider call
     * @param operation the operation name for logging
     */
    private void rethrowIfInterrupted(TypeSafeException failure, String operation) {
        InterruptedException interrupted = null;
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException found) {
                interrupted = found;
                break;
            }
        }
        var closedByInterrupt = hasCause(failure, ClosedByInterruptException.class);
        if (interrupted == null && !closedByInterrupt && !Thread.currentThread().isInterrupted()) {
            return;
        }
        Thread.currentThread().interrupt();
        if (interrupted == null) {
            interrupted = new InterruptedException("TypeSafe " + operation + " call was interrupted");
            interrupted.initCause(failure);
        }
        logger.debug(
                "TypeSafe call interrupted: service={}, provider={}, operation={}",
                getName(),
                TypeSafeModelFactory.PROVIDER,
                operation);
        var cancelled = new CancellationException("TypeSafe " + operation + " call was interrupted");
        cancelled.initCause(interrupted);
        throw cancelled;
    }

    /**
     * Checks whether a throwable of the given type is in the failure's cause chain.
     *
     * @param failure the failure to search
     * @param type the throwable type to look for
     * @return true when the chain holds one
     */
    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Picks the failure reason, keeping an unavailable provider apart from a response that failed validation.
     *
     * @param failure the TypeSafe exception
     * @return the failure reason
     */
    private static FailureReason failureReason(TypeSafeException failure) {
        return switch (failure) {
            case TypeSafeApiResponseValidationException ignored -> FailureReason.INVALID_RESPONSE;
            case TypeSafeApiConnectionException ignored -> FailureReason.UNAVAILABLE;
            case TypeSafeApiException ignored -> FailureReason.UNAVAILABLE;
            default -> FailureReason.INVALID_RESPONSE;
        };
    }

    /**
     * Logs one failed provider call. The message keeps the prefix "TypeSafe <label> failed with
     * reason <reason>" and appends bounded fields. The exception is not attached and its message is
     * not logged, because provider text can echo the input and transport errors can carry
     * credentials or endpoints.
     *
     * @param label the label for the log line
     * @param operation the operation name for logging
     * @param reason the failure reason
     * @param failure the failed call
     * @param started the call's start time, from {@link System#nanoTime()}
     */
    private void logFailure(
            String label,
            String operation,
            FailureReason reason,
            RuntimeException failure,
            long started) {
        var elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        logger.warn(
                "TypeSafe {} failed with reason {}: service={}, provider={}, operation={},"
                        + " cause={}, status={}, attempts={}, elapsedMs={}, exception={},"
                        + " requestId={}",
                label,
                reason,
                getName(),
                TypeSafeModelFactory.PROVIDER,
                operation,
                causeCategory(failure),
                statusClass(failure),
                ATTEMPTS,
                elapsedMs,
                failure.getClass().getSimpleName(),
                requestId(failure));
    }

    /**
     * Names the kind of failure from a fixed set, so operators can tell transport from content.
     *
     * @param failure the failure to categorize
     * @return a short category name for the log line
     */
    private static String causeCategory(Throwable failure) {
        if (hasTimeout(failure)) {
            return "timeout";
        }
        return switch (failure) {
            case TypeSafeApiResponseValidationException ignored -> INVALID_RESPONSE_CAUSE;
            case TypeSafeRateLimitException ignored -> "rate_limited";
            case TypeSafeApiException api when api.status() == 429 -> "rate_limited";
            case TypeSafeApiException api when api.status() >= 400 && api.status() < 500 ->
                    "http_4xx";
            case TypeSafeApiException api when api.status() >= 500 && api.status() < 600 ->
                    "http_5xx";
            case TypeSafeApiConnectionException ignored -> "connection";
            case TypeSafeApiException ignored -> "other";
            case TypeSafeException ignored -> INVALID_RESPONSE_CAUSE;
            case IllegalArgumentException ignored -> INVALID_RESPONSE_CAUSE;
            default -> "other";
        };
    }

    /**
     * Checks whether a timeout is in the failure's cause chain.
     *
     * @param failure the failure to search
     * @return true when a timeout is found
     */
    private static boolean hasTimeout(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof TypeSafeApiTimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the failure's HTTP status class for the log line.
     *
     * @param failure the failure to inspect
     * @return the status class, such as "5xx", or "none" when there isn't one
     */
    private static String statusClass(Throwable failure) {
        if (failure instanceof TypeSafeApiException api && api.status() >= 100 && api.status() < 600) {
            return (api.status() / 100) + "xx";
        }
        return "none";
    }

    /**
     * Returns the provider request id carried by the failure, for the log line.
     *
     * @param failure the failure to inspect
     * @return the request id, or "none" when there isn't one
     */
    private static String requestId(Throwable failure) {
        if (failure instanceof TypeSafeApiException api && api.requestId() != null) {
            return api.requestId();
        }
        return "none";
    }

    /**
     * Logs the SDK response at TRACE when content capture is on. The line holds provider output,
     * which can echo the input.
     *
     * @param operation the operation name for logging
     * @param response the provider's response
     */
    private void traceResponse(String operation, SystemOneResponse response) {
        if (DecisionContentCapture.isEnabled() && logger.isTraceEnabled()) {
            logger.trace(
                    "TypeSafe response content: service={}, operation={}, response={}",
                    getName(),
                    operation,
                    response);
        }
    }
}
