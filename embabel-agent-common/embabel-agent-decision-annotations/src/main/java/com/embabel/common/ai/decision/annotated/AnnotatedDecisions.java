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

import com.embabel.common.ai.classification.Category;
import com.embabel.common.ai.classification.CategoryMapping;
import org.jetbrains.annotations.ApiStatus;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Reads decision specs and classifications from annotated types under one Jackson mapper, and
 * caches each result.
 * <p>
 * Explicit annotation names and ids fix provider identifiers. The mapper supplies the remaining
 * names, the question order and the ids of enum constants, the same way for a choice option and a
 * classification category. Jackson 3 mappers are immutable, so a cached spec always matches the
 * mapper it was read with. To read with other naming settings, use another mapper.
 *
 * <pre>{@code
 * AnnotatedDecision<Triage> triage = AnnotatedDecisions.using(mapper).of(Triage.class);
 * DecisionSpec spec = triage.spec();
 * }</pre>
 * <p>
 * Instances are safe to share between threads. Calls to {@link #using} with the same mapper get
 * the same instance while it is still in use, so they share its cache. The cache stores each result
 * with its type and keeps no class or class loader reachable, and the shared table of instances
 * keeps no mapper reachable. Build mappers once and reuse them: each new mapper starts with an
 * empty cache. The mapper's own Jackson caches hold types separately.
 */
@ApiStatus.Experimental
public final class AnnotatedDecisions {

    // The instance for each mapper, both held weakly. A cached decision refers to its mapper, so a
    // strongly held instance would keep its mapper key alive for good.
    private static final Map<ObjectMapper, WeakReference<AnnotatedDecisions>> INSTANCES = new WeakHashMap<>();

    private final ObjectMapper mapper;

    // Holds successful parses only. A parse failure throws out of computeValue, which stores nothing,
    // so a type that fails is read again on the next call. ClassValue stores each value with its
    // class, so an entry is collected together with the class and its loader.
    private final ClassValue<AnnotatedDecision<?>> decisions;

    // Holds successful reads only, like the decision cache.
    private final ClassValue<CategoryMapping<?>> classifications;

    private AnnotatedDecisions(ObjectMapper mapper) {
        this.mapper = mapper;
        this.decisions = new ClassValue<>() {
            @Override
            protected AnnotatedDecision<?> computeValue(Class<?> type) {
                return DecisionTypeParser.parse(type, mapper);
            }
        };
        this.classifications = new ClassValue<>() {
            @Override
            protected CategoryMapping<?> computeValue(Class<?> type) {
                return readClassification(type, mapper);
            }
        };
    }

    /**
     * Returns the instance that reads types under the given mapper. Repeated calls with the same
     * mapper return the same instance while it is still in use.
     *
     * @param mapper the mapper whose naming and ordering settings apply
     * @return the instance for the mapper
     */
    public static AnnotatedDecisions using(ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        synchronized (INSTANCES) {
            WeakReference<AnnotatedDecisions> shared = INSTANCES.get(mapper);
            AnnotatedDecisions instance = shared == null ? null : shared.get();
            if (instance == null) {
                instance = new AnnotatedDecisions(mapper);
                INSTANCES.put(mapper, new WeakReference<>(instance));
            }
            return instance;
        }
    }

    /**
     * Returns the shared instance over a default {@link JsonMapper}, with discovered Jackson modules
     * including Kotlin when available. Missing creator properties and null primitives fail.
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
        return (AnnotatedDecision<T>) decisions.get(type);
    }

    /**
     * Returns the classification read from an enum annotated with {@link Classification}. Repeated
     * calls with the same enum return the same instance.
     * <p>
     * Each constant is a category, in declaration order. A category gets the same id as a choice
     * option over the same enum: its {@link DecisionId}, or else the mapper's serialized form of the
     * constant. Its description is its {@link Described} text.
     *
     * <pre>{@code
     * CategoryMapping<Department> ticketClass = AnnotatedDecisions.defaults().classification(Department.class);
     * MappedClassificationResult<Department> team = ticketClass.map(service.classify(input, ticketClass.spec()));
     * }</pre>
     *
     * @param type the annotated enum
     * @param <E> the enum type
     * @return the mapping between the categories and the constants
     * @throws AnnotatedDecisionException if the type is not an enum, has no {@link Classification}
     *     or a blank asking value, has no constants, has a constant without {@link Described}, or has
     *     a constant whose id is blank, repeated or does not read back under the mapper. The
     *     exception lists every problem found.
     */
    @SuppressWarnings("unchecked")
    public <E extends Enum<E>> CategoryMapping<E> classification(Class<E> type) {
        Objects.requireNonNull(type, "type");
        return (CategoryMapping<E>) classifications.get(type);
    }

    /**
     * Checks an annotated enum and builds its category mapping. A constant without
     * {@link Described} is an error, as it is for a choice option, so an enum reads the same way
     * on both paths.
     *
     * @param type the type to read
     * @param mapper the mapper that writes the constants' ids
     * @return the mapping built from the enum constants and their category ids
     * @throws AnnotatedDecisionException listing every problem found
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CategoryMapping<?> readClassification(Class<?> type, ObjectMapper mapper) {
        String name = type.getSimpleName();
        if (!type.isEnum()) {
            throw new AnnotatedDecisionException(type,
                List.of(name + ": is not an enum. Declare the categories as an enum annotated with @Classification."),
                null);
        }
        List<String> problems = new ArrayList<>();
        List<Throwable> causes = new ArrayList<>();
        Classification classification = type.getAnnotation(Classification.class);
        if (classification == null) {
            problems.add(name + ": has no @Classification. Annotate the enum with @Classification(asking = \"...\").");
        } else if (classification.asking().isBlank()) {
            problems.add(name + ": @Classification has a blank asking value. "
                + "Set asking to the instructions the model receives.");
        }
        Object[] constants = type.getEnumConstants();
        if (constants.length == 0) {
            problems.add(name + ": has no constants, so the classification has no categories. "
                + "Add one constant per category to " + name + ".");
        }
        Map<Category, Enum<?>> categories = new LinkedHashMap<>();
        Set<String> ids = new HashSet<>();
        for (Object constant : constants) {
            Enum<?> entry = (Enum<?>) constant;
            String constantName = name + "." + entry.name();
            Described described = EnumEntries.annotationOf(entry, Described.class);
            if (described == null) {
                problems.add(constantName + ": category has no @Described. "
                    + "Add @Described with the category's description to " + constantName + ".");
            } else if (described.value().isBlank()) {
                problems.add(constantName + ": category has a blank @Described value. Describe what this category means.");
            }
            String id = EnumEntries.idOf(name, "category", entry, mapper, (problem, cause) -> {
                problems.add(problem);
                if (cause != null) causes.add(cause);
            });
            if (id != null && !ids.add(id)) {
                problems.add(constantName + ": repeats category id \"" + id + "\". "
                    + "Give each constant a unique @DecisionId or serialized form.");
            } else if (id != null && described != null && !described.value().isBlank()) {
                categories.put(new Category(id, described.value()), entry);
            }
        }
        if (!problems.isEmpty()) {
            throw new AnnotatedDecisionException(type, problems, causes.isEmpty() ? null : causes.getFirst());
        }
        return new CategoryMapping(classification.asking(), categories);
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
            JsonMapper.builder().findAndAddModules()
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build());
    }
}
