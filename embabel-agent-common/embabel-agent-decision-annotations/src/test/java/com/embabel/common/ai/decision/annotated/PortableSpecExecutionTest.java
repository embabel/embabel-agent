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

import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.classification.ModelProvenance;
import com.embabel.common.ai.decision.ChoiceQuestionSpec;
import com.embabel.common.ai.decision.DecisionResponse;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.PropositionQuestionSpec;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.decision.RatingQuestionSpec;
import com.embabel.common.ai.decision.RatingResult;
import com.embabel.common.ai.decision.support.StubDecisionService;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Executes the checked-in support-triage spec with only the JSON resource and the core decision
 * types. The test uses no annotated type, so a process that holds the JSON can run the spec without
 * the class it was read from.
 */
class PortableSpecExecutionTest {

    private static final ModelProvenance PROVENANCE = new ModelProvenance("stub-model", "stub");

    @Test
    void specReadFromJsonExecutesWithoutTheAnnotatedType() throws IOException {
        // tag::annotated-portable[]
        DecisionSpec spec = JsonMapper.builder().build().readValue(resourceText(), DecisionSpec.class);
        StubDecisionService stub = StubDecisionService.builder("portable-stub")
            .proposition("urgent", new PropositionResult.Answered(false, PROVENANCE))
            .choice("department", new ClassificationResult.Selected("ACCOUNT", PROVENANCE))
            .rating("severity", new RatingResult.Answered(PROVENANCE, "MEDIUM"))
            .build();

        DecisionResponse response = stub.ask("I cannot sign in since my password reset.", spec);
        // end::annotated-portable[]

        assertEquals(spec.getDefinitionId(), response.getDefinitionId());
        assertEquals(
            new PropositionResult.Answered(false, PROVENANCE),
            response.answer((PropositionQuestionSpec) spec.question("urgent")));
        assertEquals(
            new ClassificationResult.Selected("ACCOUNT", PROVENANCE),
            response.answer((ChoiceQuestionSpec) spec.question("department")));
        assertEquals(
            new RatingResult.Answered(PROVENANCE, "MEDIUM"),
            response.answer((RatingQuestionSpec) spec.question("severity")));
        assertEquals(List.of("askNative"), stub.calls());
    }

    @Test
    void thisTestUsesNoTypeFromTheAnnotatedPackage() {
        // The test shares the annotated package, so imports alone cannot show independence.
        // The class file shows every type it refers to.
        JavaClass self = new ClassFileImporter().importClass(PortableSpecExecutionTest.class);
        String annotatedPackage = PortableSpecExecutionTest.class.getPackageName();

        List<String> used = self.getDirectDependenciesFromSelf().stream()
            .map(Dependency::getTargetClass)
            .filter(target -> target.getPackageName().equals(annotatedPackage))
            .filter(target -> !target.getName().startsWith(PortableSpecExecutionTest.class.getName()))
            .map(JavaClass::getName)
            .distinct()
            .toList();

        assertEquals(List.of(), used);
    }

    private static String resourceText() throws IOException {
        try (InputStream in = PortableSpecExecutionTest.class.getResourceAsStream("/support-triage-spec.json")) {
            assertNotNull(in, "Missing test resource /support-triage-spec.json");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
