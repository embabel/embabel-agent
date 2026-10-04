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
package com.embabel.common.ai.classification;

import static org.junit.jupiter.api.Assertions.*;

import com.embabel.common.ai.decision.PropositionResult;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

class ResultJsonJavaTest {

    private static final ModelProvenance BARE = new ModelProvenance("jev-latest", "typesafe");

    private static final ModelProvenance FULL =
            new ModelProvenance("jev-latest", "typesafe", "2026-09", "req-42");

    private final JsonMapper mapper = JsonMapper.builder().build();

    /**
     * Checks that a value writes JSON equal to the expected tree and reads back equal to itself.
     *
     * @param value the value to write
     * @param json the JSON it must write, compared as a tree so layout doesn't matter
     * @param type the type to read the JSON back as
     */
    private void assertRoundTrip(Object value, String json, Class<?> type) {
        var written = mapper.writeValueAsString(value);
        assertEquals(mapper.readTree(json), mapper.readTree(written));
        assertEquals(value, mapper.readValue(written, type));
    }

    @Test
    void provenanceLeavesOutAbsentVersionAndRequestId() {
        assertRoundTrip(
                BARE,
                """
                {"modelName": "jev-latest", "provider": "typesafe"}
                """,
                ModelProvenance.class);
        assertRoundTrip(
                FULL,
                """
                {"modelName": "jev-latest", "provider": "typesafe",
                 "version": "2026-09", "requestId": "req-42"}
                """,
                ModelProvenance.class);
    }

    @Test
    void failureReasonsAndCategoriesRoundTrip() {
        assertRoundTrip(FailureReason.UNAVAILABLE, "\"unavailable\"", FailureReason.class);
        assertRoundTrip(FailureReason.INVALID_RESPONSE, "\"invalid_response\"", FailureReason.class);
        assertRoundTrip(
                new Category("a", "A"),
                """
                {"id": "a", "description": "A"}
                """,
                Category.class);
    }

    @Test
    void everyClassificationResultRoundTrips() {
        assertRoundTrip(
                new ClassificationResult.Selected("b", FULL, 0.91),
                """
                {"status": "selected", "categoryId": "b", "confidence": 0.91,
                 "provenance": {"modelName": "jev-latest", "provider": "typesafe",
                                "version": "2026-09", "requestId": "req-42"}}
                """,
                ClassificationResult.class);
        assertRoundTrip(
                new ClassificationResult.Selected("a", BARE),
                """
                {"status": "selected", "categoryId": "a",
                 "provenance": {"modelName": "jev-latest", "provider": "typesafe"}}
                """,
                ClassificationResult.class);
        assertRoundTrip(
                new ClassificationResult.NoMatch(BARE),
                """
                {"status": "no_match", "provenance": {"modelName": "jev-latest", "provider": "typesafe"}}
                """,
                ClassificationResult.class);
        assertRoundTrip(
                new ClassificationResult.Inconclusive(BARE),
                """
                {"status": "inconclusive", "provenance": {"modelName": "jev-latest", "provider": "typesafe"}}
                """,
                ClassificationResult.class);
        assertRoundTrip(
                new ClassificationResult.Failure(FailureReason.UNAVAILABLE),
                """
                {"status": "failure", "reason": "unavailable"}
                """,
                ClassificationResult.class);
    }

    @Test
    void everyPropositionResultRoundTrips() {
        assertRoundTrip(
                new PropositionResult.Answered(true, BARE, 0.93),
                """
                {"status": "answered", "answer": true, "pTrue": 0.93,
                 "provenance": {"modelName": "jev-latest", "provider": "typesafe"}}
                """,
                PropositionResult.class);
        assertRoundTrip(
                new PropositionResult.Answered(false, BARE),
                """
                {"status": "answered", "answer": false,
                 "provenance": {"modelName": "jev-latest", "provider": "typesafe"}}
                """,
                PropositionResult.class);
        assertRoundTrip(
                new PropositionResult.Inconclusive(BARE),
                """
                {"status": "inconclusive", "provenance": {"modelName": "jev-latest", "provider": "typesafe"}}
                """,
                PropositionResult.class);
        assertRoundTrip(
                new PropositionResult.Failure(FailureReason.INVALID_RESPONSE),
                """
                {"status": "failure", "reason": "invalid_response"}
                """,
                PropositionResult.class);
    }

    @Test
    void unknownMembersAreRejected() {
        /**
         * A JSON document that must be rejected.
         *
         * @param json the document
         * @param type the type it is read as
         * @param message text the error message must contain
         */
        record Case(String json, Class<?> type, String message) {}
        var cases =
                List.of(
                        new Case(
                                """
                                {"id": "a", "description": "A", "alias": "x"}
                                """,
                                Category.class,
                                "Unknown member 'alias' in Category"),
                        new Case(
                                """
                                {"modelName": "m", "provider": "p", "apiKey": "x"}
                                """,
                                ModelProvenance.class,
                                "Unknown member 'apiKey' in ModelProvenance"),
                        new Case(
                                """
                                {"status": "failure", "reason": "unavailable", "extra": 1}
                                """,
                                ClassificationResult.class,
                                "Unknown member 'extra' in ClassificationResult"),
                        new Case(
                                """
                                {"status": "failure", "reason": "unavailable", "extra": 1}
                                """,
                                PropositionResult.class,
                                "Unknown member 'extra' in PropositionResult"));
        for (var c : cases) {
            var json = c.json();
            var type = c.type();
            var error = assertThrows(DatabindException.class, () -> mapper.readValue(json, type));
            assertTrue(error.getMessage().contains(c.message()), error.getMessage());
        }
    }
}
