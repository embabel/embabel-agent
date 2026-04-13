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
package com.embabel.common.ai.model;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TokenCountEstimatorJavaUsageTest {

    @Nested
    class FactoryMethod {

        @Test
        void heuristicIsCallableFromJava() {
            var estimator = TokenCountEstimator.heuristic();
            assertNotNull(estimator);
            assertEquals(2, estimator.estimate("abcdefgh"));
        }
    }

    @Nested
    class LambdaCreation {

        @Test
        void supportsJavaLambda() {
            TokenCountEstimator<String> estimator = text -> text.length() / 3;
            assertEquals(3, estimator.estimate("123456789"));
        }
    }

    @Nested
    class DefaultImplementation {

        @Test
        void defaultInstanceIsAccessible() {
            var result = CharacterHeuristicTokenCountEstimator.DEFAULT.estimate("abcdefgh");
            assertEquals(2, result);
        }

        @Test
        void customRatioFromJava() {
            var estimator = new CharacterHeuristicTokenCountEstimator(2);
            assertEquals(4, estimator.estimate("abcdefgh"));
        }

        @Test
        void defaultConstructorFromJava() {
            var estimator = new CharacterHeuristicTokenCountEstimator();
            assertEquals(CharacterHeuristicTokenCountEstimator.DEFAULT_CHARS_PER_TOKEN, estimator.getCharsPerToken());
        }

        @Test
        void rejectsNullFromJava() {
            assertThrows(NullPointerException.class, () ->
                CharacterHeuristicTokenCountEstimator.DEFAULT.estimate(null));
        }
    }
}
