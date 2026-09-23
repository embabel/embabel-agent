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
package com.embabel.agent.decision;

import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** A closed per-key result. Handle {@link Success} and {@link Failure} exhaustively. */
@ApiStatus.Experimental
public abstract sealed class KeyOutcome<T> permits KeyOutcome.Success, KeyOutcome.Failure {
    private KeyOutcome() {
    }

    abstract T successfulValue();

    /** Validated value, complete distribution, and deterministic tie projection. */
    @ApiStatus.Experimental
    public static final class Success<T> extends KeyOutcome<T> {
        private final T value;
        private final Map<T, Double> distribution;
        private final List<T> maximizers;
        private final T firstMaximizer;
        private final @Nullable Double expectedScore;
        private final String selectedSupportId;

        Success(T value, Map<T, Double> distribution, List<T> maximizers, T firstMaximizer, @Nullable Double expectedScore, String selectedSupportId) {
            this.value = value;
            this.distribution = distribution;
            this.maximizers = maximizers;
            this.firstMaximizer = firstMaximizer;
            this.expectedScore = expectedScore;
            this.selectedSupportId = selectedSupportId;
        }

        public T getValue() {
            return value;
        }

        public Map<T, Double> getDistribution() {
            return distribution;
        }

        public List<T> getMaximizers() {
            return maximizers;
        }

        public T getFirstMaximizer() {
            return firstMaximizer;
        }

        /** Returns the zero-based expected ordinal score for ratings, or {@code null} for other decision kinds. */
        public @Nullable Double getExpectedScore() {
            return expectedScore;
        }

        String selectedSupportId() {
            return selectedSupportId;
        }

        @Override
        T successfulValue() {
            return value;
        }
    }

    /** A safe key-local failure that does not discard valid sibling answers. */
    @ApiStatus.Experimental
    public static final class Failure<T> extends KeyOutcome<T> {
        private final KeyFailure failure;
        private final DecisionSafeCode safeCode;

        Failure(KeyFailure failure, DecisionSafeCode safeCode) {
            this.failure = failure;
            this.safeCode = safeCode;
        }

        public KeyFailure getFailure() {
            return failure;
        }

        public DecisionSafeCode getSafeCode() {
            return safeCode;
        }

        @Override
        T successfulValue() {
            throw new IllegalStateException("decision key did not succeed");
        }
    }
}
