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

import com.embabel.common.ai.decision.DecisionSpec;
import org.jetbrains.annotations.ApiStatus;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private final List<String> propertyNames;

    private final ObjectMapper mapper;

    AnnotatedDecision(
        Class<T> type,
        DecisionSpec spec,
        Map<String, String> questionNames,
        List<String> propertyNames,
        ObjectMapper mapper) {
        this.type = type;
        this.spec = spec;
        this.questionNames = Collections.unmodifiableMap(new LinkedHashMap<>(questionNames));
        this.propertyNames = List.copyOf(propertyNames);
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

    // Every Jackson property name of the type in Jackson's order, questions included.
    List<String> propertyNames() {
        return propertyNames;
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
