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
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Thrown when a decision spec cannot be read from an annotated type. It holds every problem found
 * in the type, one per entry, each naming the member, the cause and the fix.
 */
@ApiStatus.Experimental
public class AnnotatedDecisionException extends IllegalArgumentException {

    private final Class<?> type;

    private final List<String> problems;

    /**
     * Creates an exception for the given type and problems.
     *
     * @param type the type that was read
     * @param problems the problems found, in the order found, which must not be empty
     * @param cause the first underlying Jackson or builder exception, or null when there is none
     */
    public AnnotatedDecisionException(Class<?> type, List<String> problems, @Nullable Throwable cause) {
        super(message(type, problems), cause);
        this.type = type;
        this.problems = List.copyOf(problems);
    }

    /**
     * Returns the type that was read.
     *
     * @return the annotated type
     */
    public Class<?> type() {
        return type;
    }

    /**
     * Returns the problems found in the type.
     *
     * @return the problems in the order found. The list cannot be modified.
     */
    public List<String> problems() {
        return problems;
    }

    private static String message(Class<?> type, List<String> problems) {
        Objects.requireNonNull(type, "type");
        if (problems.isEmpty()) {
            throw new IllegalArgumentException("An AnnotatedDecisionException needs at least one problem");
        }
        return "Cannot read a decision spec from " + type.getName() + ":\n  " + String.join("\n  ", problems);
    }
}
