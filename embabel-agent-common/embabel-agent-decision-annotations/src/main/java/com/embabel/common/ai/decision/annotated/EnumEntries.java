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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/** Enum metadata and mapper round-trip validation shared by question bindings. */
final class EnumEntries {
    private EnumEntries() { }

    record Entry(String id, @Nullable String description, Enum<?> constant) { }

    /**
     * Reads options or levels from enum constants in declaration order. Explicit ids override the mapper's
     * serialized form of each constant, and each id must read back as the same constant.
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
            Entry entry = entryOf(member, choice, enumType, (Enum<?>) constant, mapper, problems);
            if (entry != null) {
                if (!ids.add(entry.id())) {
                    problem(problems, member + ": " + enumType.getSimpleName() + " repeats id \"" + entry.id()
                        + "\". Give each constant a unique @DecisionId or serialized form.");
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
            problem(problems, member + ": " + enumName + " has no constants, so the choice has no options. "
                + "Add one constant per option to " + enumName + ".");
        }
        if (!choice && count < 2) {
            problem(problems, member + ": " + enumName + " has " + count + (count == 1 ? " constant" : " constants")
                + ", and a rating needs at least two levels. Add the levels to " + enumName + ", lowest first.");
        }
    }

    /**
     * Reads the option or level of one enum constant.
     *
     * @param member the member label used in problem messages
     * @param choice whether the entries are choice options rather than rating levels
     * @param enumType the constant's enum type
     * @param constant the constant to read
     * @return the entry read, or null after reporting a problem with it
     */
    private static @Nullable Entry entryOf(
        String member, boolean choice, Class<?> enumType, Enum<?> constant, ObjectMapper mapper,
        BiConsumer<String, Throwable> problems) {
        String enumName = enumType.getSimpleName();
        String entry = choice ? "option" : "level";
        String constantName = enumName + "." + constant.name();
        JsonNode node;
        try {
            node = mapper.valueToTree(constant);
        } catch (JacksonException e) {
            problem(problems, member + ": " + constantName + " cannot be written under this mapper (" + messageOf(e) + "). "
                + "Make each constant of " + enumName + " writable as a JSON string.", e);
            return null;
        }
        if (node == null || !node.isString()) {
            problem(problems, member + ": " + constantName + " serializes as " + node + " under this mapper, and an " + entry
                + " id must be a JSON string. Use a mapper that writes " + enumName + " constants as strings, "
                + "for example with EnumFeature.WRITE_ENUMS_USING_INDEX disabled.");
            return null;
        }
        String id = node.stringValue();
        Object readBack;
        try {
            readBack = mapper.treeToValue(node, enumType);
        } catch (JacksonException e) {
            problem(problems, member + ": " + entry + " id \"" + id + "\" of " + constantName + " does not read back under this "
                + "mapper (" + messageOf(e) + "). Make each constant of " + enumName + " readable from its serialized form.", e);
            return null;
        }
        if (readBack != constant) {
            String other = readBack == null ? "null" : enumName + "." + ((Enum<?>) readBack).name();
            problem(problems, member + ": " + entry + " id \"" + id + "\" of " + constantName + " reads back as " + other
                + " under this mapper. Give each constant of " + enumName
                + " a distinct serialized form that the mapper reads back as the same constant.");
            return null;
        }
        return describedEntry(member, choice, constant, id, problems);
    }

    /** Reads explicit ids and descriptions after the constant has passed mapper round-trip checks. */
    private static @Nullable Entry describedEntry(String member, boolean choice, Enum<?> constant,
        String id, BiConsumer<String, Throwable> problems) {
        String constantName = constant.getDeclaringClass().getSimpleName() + "." + constant.name();
        String entry = choice ? "option" : "level";
        Described described = describedOf(constant);
        if (choice && described == null) {
            problem(problems, member + ": choice option " + constantName + " has no @Described. "
                + "Add @Described with the option's description to " + constantName + ".");
            return null;
        }
        if (described != null && described.value().isBlank()) {
            problem(problems, member + ": " + constantName + " has a blank @Described value. "
                + "Describe what this " + entry + " means.");
            return null;
        }
        DecisionId explicit = idOf(constant);
        if (explicit != null && explicit.value().isBlank()) {
            problem(problems, member + ": " + constantName + " has a blank @DecisionId. Set a stable nonblank id.");
            return null;
        }
        return new Entry(explicit == null ? id : explicit.value(), described == null ? null : described.value(), constant);
    }

    /**
     * Reads the {@link Described} annotation from an enum constant's own field.
     *
     * @param constant the constant to read
     * @return the annotation, or null when the constant has none
     */
    static @Nullable Described describedOf(Enum<?> constant) {
        try {
            return constant.getDeclaringClass().getField(constant.name()).getAnnotation(Described.class);
        } catch (NoSuchFieldException e) {
            // Every enum constant has a public field of its own name.
            throw new IllegalStateException("No field for enum constant " + constant, e);
        }
    }


    static @Nullable DecisionId idOf(Enum<?> constant) {
        try {
            return constant.getDeclaringClass().getField(constant.name()).getAnnotation(DecisionId.class);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("No field for enum constant " + constant, e);
        }
    }

    private static void problem(BiConsumer<String, Throwable> problems, String message) {
        problems.accept(message, null);
    }

    private static void problem(BiConsumer<String, Throwable> problems, String message, Throwable cause) {
        problems.accept(message, cause);
    }

    private static String messageOf(JacksonException e) {
        return e.getOriginalMessage();
    }
}
