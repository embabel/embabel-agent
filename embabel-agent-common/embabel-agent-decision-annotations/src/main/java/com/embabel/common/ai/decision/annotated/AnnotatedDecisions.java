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

import org.jetbrains.annotations.ApiStatus;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Reads decision specs from annotated types under one Jackson mapper, and caches each result.
 * <p>
 * The mapper fixes question names, option ids, level ids and question order. Jackson 3 mappers
 * are immutable, so a cached spec always matches the mapper it was read with. To read with other
 * naming settings, create another instance over a mapper with those settings.
 *
 * <pre>{@code
 * AnnotatedDecision<Triage> triage = AnnotatedDecisions.using(mapper).of(Triage.class);
 * DecisionSpec spec = triage.spec();
 * }</pre>
 * <p>
 * Instances are safe to share between threads.
 */
@ApiStatus.Experimental
public final class AnnotatedDecisions {

    private final ObjectMapper mapper;

    // Holds successful parses only. A type that fails is read again on the next call.
    private final ConcurrentMap<Class<?>, AnnotatedDecision<?>> decisions = new ConcurrentHashMap<>();

    private AnnotatedDecisions(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Returns a new instance that reads types under the given mapper.
     *
     * @param mapper the mapper whose naming and ordering settings apply
     * @return a new instance with an empty cache
     */
    public static AnnotatedDecisions using(ObjectMapper mapper) {
        return new AnnotatedDecisions(Objects.requireNonNull(mapper, "mapper"));
    }

    /**
     * Returns the shared instance over a default {@link JsonMapper} that fails on missing creator
     * properties, the same default the core decision projection uses.
     *
     * @return the shared default instance
     */
    public static AnnotatedDecisions defaults() {
        return Defaults.INSTANCE;
    }

    /**
     * Returns the decision read from the given type. Repeated calls with the same type return the
     * same instance.
     *
     * @param type the annotated type
     * @param <T> the annotated type
     * @return the decision for the type
     * @throws AnnotatedDecisionException if the type cannot become a decision spec. The exception
     *     lists every problem found.
     */
    @SuppressWarnings("unchecked")
    public <T> AnnotatedDecision<T> of(Class<T> type) {
        Objects.requireNonNull(type, "type");
        AnnotatedDecision<?> cached = decisions.get(type);
        if (cached != null) {
            return (AnnotatedDecision<T>) cached;
        }
        AnnotatedDecision<T> parsed = DecisionTypeParser.parse(type, mapper);
        AnnotatedDecision<?> raced = decisions.putIfAbsent(type, parsed);
        return raced == null ? parsed : (AnnotatedDecision<T>) raced;
    }

    /**
     * Returns the mapper this instance reads types with.
     *
     * @return the mapper
     */
    public ObjectMapper mapper() {
        return mapper;
    }

    // Created on first use of defaults().
    private static final class Defaults {
        static final AnnotatedDecisions INSTANCE = using(
            JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).build());
    }
}
