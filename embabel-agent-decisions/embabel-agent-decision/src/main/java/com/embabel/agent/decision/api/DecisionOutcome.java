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
package com.embabel.agent.decision.api;

import java.util.Map;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** A closed call-level result. Handle {@link Success} and {@link Failure} exhaustively. */
@ApiStatus.Experimental
public abstract sealed class DecisionOutcome permits DecisionOutcome.Success, DecisionOutcome.Failure {
    private DecisionOutcome() {
    }

    /** Validated typed evidence and provenance for a completed call. */
    @ApiStatus.Experimental
    public static final class Success extends DecisionOutcome {
        private final DecisionProvenance provenance;
        private final @Nullable DecisionRecord record;
        private final Map<DecisionKey<?>, KeyOutcome<?>> answers;

        Success(DecisionProvenance provenance, @Nullable DecisionRecord record, Map<DecisionKey<?>, KeyOutcome<?>> answers) {
            this.provenance = provenance;
            this.record = record;
            this.answers = answers;
        }

        public DecisionProvenance getProvenance() {
            return provenance;
        }

        /** Returns the policy-safe record, or {@code null} when record mode is {@code NONE}. */
        public @Nullable DecisionRecord getRecord() {
            return record;
        }

        @SuppressWarnings("unchecked")
        public <T> KeyOutcome<T> answer(DecisionKey<T> key) {
            KeyOutcome<?> answer = answers.get(key);
            if (answer == null) {
                throw new IllegalArgumentException("decision key belongs to another request");
            }
            return (KeyOutcome<T>) answer;
        }

        public <T> T value(DecisionKey<T> key) {
            return answer(key).successfulValue();
        }
    }

    /** A safe call-level failure with facade provenance. */
    @ApiStatus.Experimental
    public static final class Failure extends DecisionOutcome {
        private final CallFailure failure;
        private final DecisionSafeCode safeCode;
        private final DecisionProvenance provenance;
        private final @Nullable DecisionRecord record;

        Failure(CallFailure failure, DecisionSafeCode safeCode, DecisionProvenance provenance, @Nullable DecisionRecord record) {
            this.failure = failure;
            this.safeCode = safeCode;
            this.provenance = provenance;
            this.record = record;
        }

        public CallFailure getFailure() {
            return failure;
        }

        public DecisionSafeCode getSafeCode() {
            return safeCode;
        }

        public DecisionProvenance getProvenance() {
            return provenance;
        }

        /** Returns the policy-safe record, or {@code null} when record mode is {@code NONE}. */
        public @Nullable DecisionRecord getRecord() {
            return record;
        }
    }
}
