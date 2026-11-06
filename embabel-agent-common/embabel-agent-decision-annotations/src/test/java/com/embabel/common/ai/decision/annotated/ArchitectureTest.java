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

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Checks the dependency rules of the annotated package. Parsing a type builds a spec and runs no
 * inference, so main classes reach only the core decision types, Jackson and the JDK.
 */
class ArchitectureTest {

    private static final String PACKAGE = "com.embabel.common.ai.decision.annotated";

    private static JavaClasses mainClasses;

    @BeforeAll
    static void importMainClasses() {
        mainClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(PACKAGE);
    }

    @Test
    void mainClassesAreImported() {
        assertFalse(mainClasses.isEmpty(), "No main classes found in " + PACKAGE);
    }

    @Test
    void mainClassesDependOnlyOnCoreDecisionTypesJacksonAndTheJdk() {
        ArchRule rule = classes()
            .that().resideInAPackage(PACKAGE + "..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                "java..",
                "tools.jackson..",
                "com.fasterxml.jackson.annotation..",
                "org.jetbrains.annotations..",
                "com.embabel.common.ai.classification..",
                "com.embabel.common.ai.decision",
                PACKAGE + "..")
            .because("the annotated package reads specs and projects answers, and needs nothing else");
        rule.check(mainClasses);
    }

    @Test
    void mainClassesDoNotReachInferenceOrSpring() {
        ArchRule rule = noClasses()
            .that().resideInAPackage(PACKAGE + "..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.embabel.common.ai.model..",
                "com.embabel.common.ai.decision.spi..",
                "com.embabel.common.ai.decision.support..",
                "org.springframework..")
            .because("parsing a type must not run or configure inference");
        rule.check(mainClasses);
    }

    @Test
    void questionAnnotationsAreReadableAtRuntimeOnFieldsMethodsAndParameters() {
        Set<ElementType> questionTargets = Set.of(ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER);
        for (Class<?> annotation : List.of(PropositionQuestion.class, ChoiceQuestion.class, RatingQuestion.class)) {
            assertRetainedAtRuntime(annotation);
            assertEquals(questionTargets, targetsOf(annotation), annotation.getSimpleName() + " targets");
        }
        assertRetainedAtRuntime(Described.class);
        assertEquals(Set.of(ElementType.FIELD), targetsOf(Described.class), "Described targets");
        assertRetainedAtRuntime(Classification.class);
        assertEquals(Set.of(ElementType.TYPE), targetsOf(Classification.class), "Classification targets");
    }

    @Test
    void askingIsRequiredOnEveryQuestionAnnotation() throws NoSuchMethodException {
        for (Class<?> annotation : List.of(PropositionQuestion.class, ChoiceQuestion.class, RatingQuestion.class)) {
            assertEquals(null, annotation.getMethod("asking").getDefaultValue(),
                annotation.getSimpleName() + ".asking must have no default");
        }
        assertEquals(null, Described.class.getMethod("value").getDefaultValue(), "Described.value must have no default");
        assertEquals(null, Classification.class.getMethod("asking").getDefaultValue(),
            "Classification.asking must have no default");
    }

    private static void assertRetainedAtRuntime(Class<?> annotation) {
        Retention retention = annotation.getAnnotation(Retention.class);
        assertNotNull(retention, annotation.getSimpleName() + " has no @Retention");
        assertEquals(RetentionPolicy.RUNTIME, retention.value(), annotation.getSimpleName() + " retention");
    }

    private static Set<ElementType> targetsOf(Class<?> annotation) {
        Target target = annotation.getAnnotation(Target.class);
        assertNotNull(target, annotation.getSimpleName() + " has no @Target");
        return Set.of(target.value());
    }
}
