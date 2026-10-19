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

import org.jetbrains.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Enum metadata and mapper round-trip validation shared by choice options, rating levels and
 * classification categories, so the same enum gets the same ids on every path.
 */
final class EnumEntries {
    private EnumEntries() { }

    record Entry(String id, @Nullable String description, Enum<?> constant) { }

    /**
     * Reads options or levels from enum constants in declaration order, with the ids that
     * {@link #idOf} gives them.
     *
     * @param member the member label used in problem messages
     * @param choice whether the entries are choice options rather than rating levels
     * @param enumType the enum type to read
     * @return the entries read, one per constant that reads back correctly
     */
    static List<Entry> read(String member, boolean choice, Class<?> enumType, ObjectMapper mapper,
        BiConsumer<String, Throwable> problems) {
        Object[] constants = enumType.getEnumConstants();
        checkConstantCount(member, choice, enumType.getSimpleName(), constants.length, problems);
        List<Entry> entries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object constant : constants) {
            Entry entry = entryOf(member, choice, (Enum<?>) constant, mapper, problems);
            if (entry != null) {
                if (!ids.add(entry.id())) {
                    problems.accept(member + ": " + enumType.getSimpleName() + " repeats id \"" + entry.id()
                        + "\". Give each constant a unique @DecisionId or serialized form.", null);
                }
                entries.add(entry);
            }
        }
        return entries;
    }

    /**
     * Checks that an enum backing a choice or rating has enough constants.
     *
     * @param member the member label used in problem messages
     * @param choice whether the entries are choice options rather than rating levels
     * @param enumName the enum type's simple name
     * @param count the number of constants the enum declares
     */
    private static void checkConstantCount(
        String member, boolean choice, String enumName, int count, BiConsumer<String, Throwable> problems) {
        if (choice && count == 0) {
            problems.accept(member + ": " + enumName + " has no constants, so the choice has no options. "
                + "Add one constant per option to " + enumName + ".", null);
        }
        if (!choice && count < 2) {
            problems.accept(member + ": " + enumName + " has " + count + (count == 1 ? " constant" : " constants")
                + ", and a rating needs at least two levels. Add the levels to " + enumName + ", lowest first.", null);
        }
    }

    /**
     * Reads the option or level of one enum constant.
     *
     * @param member the member label used in problem messages
     * @param choice whether the entries are choice options rather than rating levels
     * @param constant the constant to read
     * @return the entry read, or null after reporting a problem with it
     */
    private static @Nullable Entry entryOf(
        String member, boolean choice, Enum<?> constant, ObjectMapper mapper, BiConsumer<String, Throwable> problems) {
        String entry = choice ? "option" : "level";
        String id = idOf(member, entry, constant, mapper, problems);
        if (id == null) {
            return null;
        }
        String constantName = constant.getDeclaringClass().getSimpleName() + "." + constant.name();
        Described described = annotationOf(constant, Described.class);
        if (choice && described == null) {
            problems.accept(member + ": choice option " + constantName + " has no @Described. "
                + "Add @Described with the option's description to " + constantName + ".", null);
            return null;
        }
        if (described != null && described.value().isBlank()) {
            problems.accept(member + ": " + constantName + " has a blank @Described value. "
                + "Describe what this " + entry + " means.", null);
            return null;
        }
        return new Entry(id, described == null ? null : described.value(), constant);
    }

    /**
     * Works out the id of one enum constant: its {@link DecisionId} when it has one, otherwise the
     * mapper's serialized form. Either way the serialized form must read back as the same constant,
     * so the mapper can always bind the enum.
     *
     * @param member the member label used in problem messages
     * @param entry what the id belongs to, such as "option", "level" or "category"
     * @param constant the constant to read
     * @return the id, or null after reporting a problem with it
     */
    static @Nullable String idOf(
        String member, String entry, Enum<?> constant, ObjectMapper mapper, BiConsumer<String, Throwable> problems) {
        Class<?> enumType = constant.getDeclaringClass();
        String enumName = enumType.getSimpleName();
        String constantName = enumName + "." + constant.name();
        JsonNode node;
        try {
            node = mapper.valueToTree(constant);
        } catch (JacksonException e) {
            problems.accept(member + ": " + constantName + " cannot be written under this mapper ("
                + DecisionTypeParser.messageOf(e) + "). Make each constant of " + enumName + " writable as a JSON string.", e);
            return null;
        }
        if (node == null || !node.isString()) {
            problems.accept(member + ": " + constantName + " serializes as " + node + " under this mapper, and "
                + (entry.equals("option") ? "an " : "a ") + entry + " id must be a JSON string. Use a mapper that writes "
                + enumName + " constants as strings, for example with EnumFeature.WRITE_ENUMS_USING_INDEX disabled.", null);
            return null;
        }
        String serialized = node.stringValue();
        Object readBack;
        try {
            readBack = mapper.treeToValue(node, enumType);
        } catch (JacksonException e) {
            problems.accept(member + ": " + entry + " id \"" + serialized + "\" of " + constantName
                + " does not read back under this mapper (" + DecisionTypeParser.messageOf(e) + "). Make each constant of "
                + enumName + " readable from its serialized form.", e);
            return null;
        }
        if (readBack != constant) {
            String other = readBack == null ? "null" : enumName + "." + ((Enum<?>) readBack).name();
            problems.accept(member + ": " + entry + " id \"" + serialized + "\" of " + constantName + " reads back as "
                + other + " under this mapper. Give each constant of " + enumName
                + " a distinct serialized form that the mapper reads back as the same constant.", null);
            return null;
        }
        DecisionId explicit = annotationOf(constant, DecisionId.class);
        if (explicit == null) {
            return serialized;
        }
        if (explicit.value().isBlank()) {
            problems.accept(member + ": " + constantName + " has a blank @DecisionId. Set a stable nonblank id.", null);
            return null;
        }
        return explicit.value();
    }

    /**
     * Reads an annotation from an enum constant's own field.
     *
     * @param constant the constant to read
     * @param annotation the annotation type to look for
     * @return the annotation, or null when the constant has none
     */
    static <A extends Annotation> @Nullable A annotationOf(Enum<?> constant, Class<A> annotation) {
        try {
            return constant.getDeclaringClass().getField(constant.name()).getAnnotation(annotation);
        } catch (NoSuchFieldException e) {
            // Every enum constant has a public field of its own name.
            throw new IllegalStateException("No field for enum constant " + constant, e);
        }
    }
}
