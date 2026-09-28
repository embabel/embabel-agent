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
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Checks that the spec read from the support-triage record serializes to the checked-in JSON, and
 * that the JSON names no Java class.
 */
class PortableSpecTest {

    static final String RESOURCE = "/support-triage-spec.json";

    // A dotted lower-case package followed by an upper-case class name, such as com.acme.Triage.
    private static final Pattern QUALIFIED_CLASS_NAME = Pattern.compile("[a-z]+\\.[a-z]+\\.[A-Z]");

    enum Department {
        @Described("Payments, invoicing, refunds")
        BILLING,
        @Described("Bugs, outages, integrations")
        TECHNICAL,
        @Described("Accounts, sign-in, permissions")
        ACCOUNT,
    }

    enum Severity {
        @Described("Cosmetic or no customer impact")
        LOW,
        @Described("Work is slowed for one customer")
        MEDIUM,
        @Described("Work is blocked for one customer")
        HIGH,
        @Described("Work is blocked for many customers")
        CRITICAL,
    }

    record SupportTriage(
        @PropositionQuestion(asking = "Does this ticket convey urgency?") boolean urgent,
        @ChoiceQuestion(asking = "Which team should handle this ticket?") Department department,
        @RatingQuestion(asking = "How severe is the customer impact?") Severity severity) {
    }

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void generatedSpecJsonEqualsTheResource() throws IOException {
        DecisionSpec spec = AnnotatedDecisions.using(mapper).of(SupportTriage.class).spec();

        String json = mapper.writeValueAsString(spec);

        assertEquals(mapper.readTree(resourceText()), mapper.readTree(json), json);
    }

    @Test
    void generatedSpecJsonNamesNoJavaClass() {
        DecisionSpec spec = AnnotatedDecisions.using(mapper).of(SupportTriage.class).spec();

        String json = mapper.writeValueAsString(spec);

        assertFalse(QUALIFIED_CLASS_NAME.matcher(json).find(), json);
        assertFalse(json.contains("@class"), json);
        assertFalse(json.contains(SupportTriage.class.getSimpleName()), json);
        assertFalse(json.contains(Department.class.getSimpleName()), json);
    }

    @Test
    void resourceNamesNoJavaClass() throws IOException {
        String text = resourceText();

        assertFalse(QUALIFIED_CLASS_NAME.matcher(text).find(), text);
        assertFalse(text.contains("@class"), text);
    }

    @Test
    void resourceReadsBackToTheGeneratedSpec() throws IOException {
        DecisionSpec spec = AnnotatedDecisions.using(mapper).of(SupportTriage.class).spec();

        DecisionSpec read = mapper.readValue(resourceText(), DecisionSpec.class);

        assertEquals(spec, read);
    }

    static String resourceText() throws IOException {
        try (InputStream in = PortableSpecTest.class.getResourceAsStream(RESOURCE)) {
            assertNotNull(in, "Missing test resource " + RESOURCE);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
