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

public class TokenCounterJavaUsageTest {

    @Nested
    class FactoryMethod {

        @Test
        void heuristicIsCallableFromJava() {
            var counter = TokenCounter.heuristic();
            assertNotNull(counter);
            assertEquals(2, counter.estimateTokens("abcdefgh"));
        }
    }

    @Nested
    class LambdaCreation {

        @Test
        void supportsJavaLambda() {
            TokenCounter<String> counter = text -> text.length() / 3;
            assertEquals(3, counter.estimateTokens("123456789"));
        }
    }

    @Nested
    class DefaultImplementation {

        @Test
        void defaultInstanceIsAccessible() {
            var result = CharacterHeuristicTokenCounter.DEFAULT.estimateTokens("abcdefgh");
            assertEquals(2, result);
        }

        @Test
        void customRatioFromJava() {
            var counter = new CharacterHeuristicTokenCounter(2);
            assertEquals(4, counter.estimateTokens("abcdefgh"));
        }

        @Test
        void defaultConstructorFromJava() {
            var counter = new CharacterHeuristicTokenCounter();
            assertEquals(CharacterHeuristicTokenCounter.DEFAULT_CHARS_PER_TOKEN, counter.getCharsPerToken());
        }

        @Test
        void rejectsNullFromJava() {
            assertThrows(NullPointerException.class, () ->
                CharacterHeuristicTokenCounter.DEFAULT.estimateTokens(null));
        }
    }
}
