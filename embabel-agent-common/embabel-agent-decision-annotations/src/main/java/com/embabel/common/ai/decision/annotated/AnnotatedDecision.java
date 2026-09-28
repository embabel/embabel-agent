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
package com.embabel.common.ai.decision.annotated;

import com.embabel.common.ai.decision.DecisionProjection;
import com.embabel.common.ai.decision.DecisionProjectionException;
import com.embabel.common.ai.decision.DecisionResponse;
import com.embabel.common.ai.decision.DecisionSpec;
import org.jetbrains.annotations.ApiStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The decision spec read from one annotated type under one Jackson mapper. Get one from
 * {@link AnnotatedDecisions#of(Class)}.
 * <p>
 * Instances are immutable and safe to share between threads.
 *
 * @param <T> the annotated type
 */
@ApiStatus.Experimental
public final class AnnotatedDecision<T> {

    private final Class<T> type;

    private final DecisionSpec spec;

    private final Map<String, String> questionNames;

    // Properties Jackson sets that are not questions, in Jackson's order. Projection takes their values from otherProperties.
    private final List<String> otherNames;

    private final ObjectMapper mapper;

    AnnotatedDecision(
        Class<T> type,
        DecisionSpec spec,
        Map<String, String> questionNames,
        List<String> settableNames,
        ObjectMapper mapper) {
        this.type = type;
        this.spec = spec;
        this.questionNames = Collections.unmodifiableMap(new LinkedHashMap<>(questionNames));
        this.otherNames = settableNames.stream().filter(name -> !questionNames.containsValue(name)).toList();
        this.mapper = mapper;
    }

    /**
     * Returns the annotated type.
     *
     * @return the type the spec was read from
     */
    public Class<T> type() {
        return type;
    }

    /**
     * Returns the decision spec read from the type.
     *
     * @return the spec, with one question per annotated property in Jackson's property order
     */
    public DecisionSpec spec() {
        return spec;
    }

    /**
     * Returns the question name of each annotated Java member.
     *
     * @return a map from Java member name to question name, in spec order. The map cannot be modified.
     */
    public Map<String, String> questionNames() {
        return questionNames;
    }

    /**
     * Projects a response to this decision's spec into the annotated type.
     * <p>
     * The response must match this decision's spec, as {@link DecisionResponse#requireMatches}
     * checks. The answers then convert to the type through the mapper that read it, as in
     * {@link DecisionProjection#of(DecisionResponse, Class, ObjectMapper)}. A type with properties
     * that carry no question annotation needs {@link #project(DecisionResponse, Map)}.
     *
     * @param response the response to project
     * @return the projected value together with the response
     * @throws DecisionProjectionException if the response does not match the spec, the type has
     *     properties the answers do not set, an answer has no representable value, or the values do
     *     not fit the type
     */
    public DecisionProjection<T> project(DecisionResponse response) {
        requireSpecOf(response);
        List<String> problems = otherPropertyProblems(Map.of());
        if (!problems.isEmpty()) {
            throw new DecisionProjectionException(String.join("\n", problems), List.of(), null);
        }
        return DecisionProjection.of(response, type, mapper);
    }

    /**
     * Projects a response to this decision's spec into the annotated type, taking the values of the
     * properties without question annotations from {@code otherProperties}.
     * <p>
     * The keys are Jackson property names under the mapper that read the type. Every property that
     * Jackson sets and that is not a question needs a key, and no other key is accepted. A key
     * mapped to null counts as supplied. The response must answer this decision's spec, as in
     * {@link #project(DecisionResponse)}.
     * <p>
     * This method returns the value alone. The core {@link DecisionProjection} holds only values
     * converted from the answers, so it cannot carry the other properties. The caller keeps the
     * response for its provenance.
     *
     * @param response the response to project
     * @param otherProperties the values of the non-question properties, keyed by property name
     * @return the projected value
     * @throws DecisionProjectionException if the response does not match the spec, a key is missing,
     *     unknown or names a question, an answer has no representable value, or the values do not
     *     fit the type. The message lists every key problem at once.
     */
    public T project(DecisionResponse response, Map<String, ?> otherProperties) {
        Objects.requireNonNull(otherProperties, "otherProperties");
        requireSpecOf(response);
        List<String> problems = otherPropertyProblems(otherProperties);
        if (!problems.isEmpty()) {
            throw new DecisionProjectionException(String.join("\n", problems), List.of(), null);
        }
        Map<String, Object> values = new LinkedHashMap<>(DecisionProjection.answeredValues(response));
        values.putAll(otherProperties);
        try {
            return mapper.convertValue(values, type);
        } catch (JacksonException e) {
            throw new DecisionProjectionException(
                "Cannot map the answered values and otherProperties to " + type.getName(), List.of(), e);
        }
    }

    /**
     * Checks that the response matches this decision's spec before projecting it.
     * <p>
     * A response that matches has one answer per question, in order, of the right kind and with
     * the same options or levels, so its answers fit this type.
     *
     * @param response the response to check
     */
    private void requireSpecOf(DecisionResponse response) {
        Objects.requireNonNull(response, "response");
        try {
            response.requireMatches(spec);
        } catch (IllegalArgumentException e) {
            throw new DecisionProjectionException(
                e.getMessage() + " " + type.getSimpleName() + " reads a different spec."
                    + " Ask with AnnotatedDecision.spec() for this type and mapper.",
                List.of(),
                e);
        }
    }

    /**
     * Checks the supplied other properties against the properties this type actually needs.
     *
     * @param otherProperties the values passed for the non-question properties
     * @return one problem per issue found: a missing key, an unknown key or a key naming a question
     */
    private List<String> otherPropertyProblems(Map<String, ?> otherProperties) {
        String name = type.getSimpleName();
        List<String> missing = otherNames.stream().filter(key -> !otherProperties.containsKey(key)).toList();
        // Question keys in spec order and unknown keys sorted, so the message does not depend on map order.
        List<String> questions = questionNames.values().stream().filter(otherProperties::containsKey).toList();
        List<String> unknown = otherProperties.keySet().stream()
            .filter(key -> !questionNames.containsValue(key) && !otherNames.contains(key))
            .sorted()
            .toList();
        List<String> problems = new ArrayList<>();
        if (!missing.isEmpty()) {
            problems.add(name + " needs values for " + missing + ". Pass them in otherProperties.");
        }
        if (!unknown.isEmpty()) {
            problems.add(name + " has no property " + unknown + " to set from otherProperties. Use only the keys "
                + otherNames + ".");
        }
        if (!questions.isEmpty()) {
            problems.add(name + " takes " + questions + " from the response answers. Remove them from otherProperties.");
        }
        return problems;
    }

    // The mapper that named the questions. Projection reads answers back through the same mapper.
    ObjectMapper mapper() {
        return mapper;
    }

    @Override
    public String toString() {
        return "AnnotatedDecision(type=" + type.getName() + ", questions=" + questionNames.values() + ")";
    }
}
