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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the problems reported for types that cannot become a decision spec. Every problem names
 * the type, the member, the cause and the fix. Parsing runs no inference, so no decision service
 * appears here.
 */
class AnnotatedSpecErrorsTest {

    private static final String URGENT = "Does this ticket convey urgency?";
    private static final String TEAM = "Which team should handle this?";
    private static final String SEVERITY = "How severe is the impact?";

    enum Department {
        @Described("Payments, invoicing, refunds")
        BILLING,
        @Described("Bugs, outages, integrations")
        TECHNICAL,
    }

    enum UndescribedDepartment {
        BILLING,
        @Described("Bugs, outages, integrations")
        TECHNICAL,
    }

    enum NoDepartments {
    }

    enum OneLevel {
        ONLY,
    }

    enum Severity {
        LOW,
        HIGH,
    }

    enum SharedId {
        @Described("Payments, invoicing, refunds")
        @JsonProperty("same")
        BILLING,
        @Described("Bugs, outages, integrations")
        @JsonProperty("same")
        TECHNICAL,
    }

    enum DefaultedDepartment {
        @Described("Payments, invoicing, refunds")
        BILLING,
        @Described("Bugs, outages, integrations")
        @JsonEnumDefaultValue
        TECHNICAL,
    }

    // Fixtures that parse.

    static final class FieldOnly {
        @PropositionQuestion(asking = URGENT)
        private boolean urgent;

        public boolean isUrgent() {
            return urgent;
        }
    }

    static final class GetterOnly {
        private boolean urgent;

        @PropositionQuestion(asking = URGENT)
        public boolean isUrgent() {
            return urgent;
        }
    }

    static final class CreatorParameterOnly {
        private final boolean urgent;

        @JsonCreator
        CreatorParameterOnly(@PropositionQuestion(asking = URGENT) boolean urgent) {
            this.urgent = urgent;
        }

        public boolean isUrgent() {
            return urgent;
        }
    }

    static final class SameAskingOnSeveralMembers {
        @PropositionQuestion(asking = URGENT)
        private boolean urgent;

        @PropositionQuestion(asking = URGENT)
        public boolean isUrgent() {
            return urgent;
        }
    }

    // Fixtures that fail.

    record IgnoredComponent(
        @JsonIgnore @PropositionQuestion(asking = URGENT) boolean urgent,
        @ChoiceQuestion(asking = TEAM) Department department) {
    }

    @JsonIgnoreProperties({"urgent"})
    record IgnoredByType(
        @PropositionQuestion(asking = URGENT) boolean urgent,
        @ChoiceQuestion(asking = TEAM) Department department) {
    }

    static final class IgnoredField {
        @JsonIgnore
        @PropositionQuestion(asking = URGENT)
        public boolean urgent;

        @ChoiceQuestion(asking = TEAM)
        public Department department;
    }

    record NotAProperty(@ChoiceQuestion(asking = TEAM) Department department) {
        @PropositionQuestion(asking = URGENT)
        public boolean looksUrgent() {
            return false;
        }
    }

    interface UrgencyFlag {
        @PropositionQuestion(asking = URGENT)
        boolean urgent();
    }

    interface EscalationFlag {
        @PropositionQuestion(asking = URGENT)
        default boolean looksUrgent() {
            return false;
        }
    }

    interface InheritedEscalationFlag extends EscalationFlag {
    }

    // The record accessor overrides the annotated interface method, and Jackson merges the two.
    record InterfaceCovered(boolean urgent, @ChoiceQuestion(asking = TEAM) Department department) implements UrgencyFlag {
    }

    // Under bean naming urgent() is not a getter, so the interface annotation belongs to no property.
    static final class InterfaceNotAProperty implements UrgencyFlag {
        private final boolean urgent;

        private final Department team;

        @JsonCreator
        InterfaceNotAProperty(
            @JsonProperty("urgent") boolean urgent,
            @JsonProperty("team") @ChoiceQuestion(asking = TEAM) Department team) {
            this.urgent = urgent;
            this.team = team;
        }

        @Override
        public boolean urgent() {
            return urgent;
        }

        public Department getTeam() {
            return team;
        }
    }

    record DefaultMethodNotAProperty(@ChoiceQuestion(asking = TEAM) Department department)
        implements InheritedEscalationFlag, EscalationFlag {
    }

    interface UrgentGetter {
        @PropositionQuestion(asking = URGENT)
        boolean isUrgent();
    }

    static class UrgentBase {
        private boolean urgent;

        public boolean isUrgent() {
            return urgent;
        }

        public void setUrgent(boolean urgent) {
            this.urgent = urgent;
        }
    }

    // The getter comes from a superclass that does not implement the annotated interface.
    static final class InterfaceCoveredBySuperclass extends UrgentBase implements UrgentGetter {
        @ChoiceQuestion(asking = TEAM)
        public Department department;
    }

    static class FlaggedBase implements EscalationFlag {
        @ChoiceQuestion(asking = TEAM)
        public Department department;
    }

    static final class FlaggedSub extends FlaggedBase implements EscalationFlag {
    }

    static final class SetterParameter {
        private boolean urgent;

        @ChoiceQuestion(asking = TEAM)
        public Department department;

        public boolean isUrgent() {
            return urgent;
        }

        public void setUrgent(@PropositionQuestion(asking = URGENT) boolean urgent) {
            this.urgent = urgent;
        }
    }

    static final class NoMutator {
        @PropositionQuestion(asking = URGENT)
        public boolean isUrgent() {
            return true;
        }
    }

    record StringChoice(@ChoiceQuestion(asking = TEAM) String team) {
    }

    record IntProposition(@PropositionQuestion(asking = URGENT) int urgent) {
    }

    record IntegerRating(@RatingQuestion(asking = SEVERITY) Integer severity) {
    }

    record Picked<E extends Enum<E>>(@ChoiceQuestion(asking = TEAM) E pick) {
    }

    record TwoKinds(@PropositionQuestion(asking = URGENT) @ChoiceQuestion(asking = TEAM) boolean urgent) {
    }

    static final class AskingDiffers {
        @PropositionQuestion(asking = URGENT)
        private boolean urgent;

        @PropositionQuestion(asking = "Is this urgent?")
        public boolean isUrgent() {
            return urgent;
        }
    }

    record BlankAsking(@PropositionQuestion(asking = "  ") boolean urgent) {
    }

    record MissingDescription(@ChoiceQuestion(asking = TEAM) UndescribedDepartment department) {
    }

    record EmptyChoice(@ChoiceQuestion(asking = TEAM) NoDepartments department) {
    }

    record SingleLevel(@RatingQuestion(asking = SEVERITY) OneLevel severity) {
    }

    record IndexedIds(@ChoiceQuestion(asking = TEAM) Department department) {
    }

    record DefaultedIds(@ChoiceQuestion(asking = TEAM) DefaultedDepartment department) {
    }

    record SharedIds(@ChoiceQuestion(asking = TEAM) SharedId department) {
    }

    static final class DuplicateName {
        @JsonProperty("urgent")
        @PropositionQuestion(asking = URGENT)
        public boolean urgent;

        @JsonProperty("urgent")
        public boolean hot;
    }

    static final class MergedName {
        @JsonProperty("urgent")
        @PropositionQuestion(asking = URGENT)
        public boolean flag;

        @JsonProperty("urgent")
        public boolean isHot() {
            return flag;
        }
    }

    record NoQuestions(String sourceId, boolean urgent) {
    }

    record SeveralProblems(
        @PropositionQuestion(asking = "") boolean urgent,
        @ChoiceQuestion(asking = TEAM) String team,
        @RatingQuestion(asking = SEVERITY) OneLevel severity) {

        @PropositionQuestion(asking = URGENT)
        public boolean looksUrgent() {
            return false;
        }
    }

    private static AnnotatedDecisionException failure(Class<?> type) {
        return failure(AnnotatedDecisions.defaults(), type);
    }

    private static AnnotatedDecisionException failure(AnnotatedDecisions decisions, Class<?> type) {
        AnnotatedDecisionException failure = assertThrows(AnnotatedDecisionException.class, () -> decisions.of(type));
        assertSame(type, failure.type());
        assertEquals(
            "Cannot read a decision spec from " + type.getName() + ":\n  " + String.join("\n  ", failure.problems()),
            failure.getMessage());
        return failure;
    }

    private static void assertOnlyProblem(Class<?> type, String problem) {
        assertEquals(List.of(problem), failure(type).problems());
    }

    @Test
    void annotationOnFieldOnlyParses() {
        assertEquals(Map.of("urgent", "urgent"), AnnotatedDecisions.defaults().of(FieldOnly.class).questionNames());
    }

    @Test
    void annotationOnGetterOnlyParses() {
        assertEquals(Map.of("urgent", "urgent"), AnnotatedDecisions.defaults().of(GetterOnly.class).questionNames());
    }

    @Test
    void annotationOnCreatorParameterOnlyParses() {
        assertEquals(Map.of("urgent", "urgent"),
            AnnotatedDecisions.defaults().of(CreatorParameterOnly.class).questionNames());
    }

    @Test
    void sameAnnotationOnSeveralMembersOfOnePropertyIsOneQuestion() {
        assertEquals(1, AnnotatedDecisions.defaults().of(SameAskingOnSeveralMembers.class).spec().getQuestions().size());
    }

    @Test
    void exceptionIsAnIllegalArgumentExceptionWithAnUnmodifiableProblemList() {
        AnnotatedDecisionException failure = failure(NoQuestions.class);
        List<String> problems = failure.problems();

        assertInstanceOf(IllegalArgumentException.class, failure);
        assertThrows(UnsupportedOperationException.class, () -> problems.add("x"));
        assertNull(failure.getCause());
    }

    @Test
    void ignoredRecordComponentIsRejected() {
        assertOnlyProblem(IgnoredComponent.class,
            "IgnoredComponent.urgent: carries @PropositionQuestion but Jackson ignores the property "
                + "(@JsonIgnore, @JsonIgnoreProperties or @JsonIncludeProperties). "
                + "Remove the question annotation or stop ignoring the property.");
    }

    @Test
    void propertyIgnoredByTheTypeIsRejected() {
        assertOnlyProblem(IgnoredByType.class,
            "IgnoredByType.urgent: carries @PropositionQuestion but Jackson ignores the property "
                + "(@JsonIgnore, @JsonIgnoreProperties or @JsonIncludeProperties). "
                + "Remove the question annotation or stop ignoring the property.");
    }

    @Test
    void ignoredFieldIsRejected() {
        assertOnlyProblem(IgnoredField.class,
            "IgnoredField.urgent: carries @PropositionQuestion but Jackson ignores the property "
                + "(@JsonIgnore, @JsonIgnoreProperties or @JsonIncludeProperties). "
                + "Remove the question annotation or stop ignoring the property.");
    }

    @Test
    void annotatedMethodThatIsNotAPropertyIsRejected() {
        assertOnlyProblem(NotAProperty.class,
            "NotAProperty.looksUrgent(): carries @PropositionQuestion but is not a Jackson property. "
                + "Move the annotation to a record component, field, getter or creator parameter.");
    }

    @Test
    void interfaceMethodOverriddenByAPropertyGetterParses() {
        assertEquals(Map.of("urgent", "urgent", "department", "department"),
            AnnotatedDecisions.defaults().of(InterfaceCovered.class).questionNames());
        assertEquals(Map.of("urgent", "urgent", "department", "department"),
            AnnotatedDecisions.defaults().of(InterfaceCoveredBySuperclass.class).questionNames());
    }

    @Test
    void annotatedInterfaceMethodThatIsNotAPropertyIsRejected() {
        assertOnlyProblem(InterfaceNotAProperty.class,
            "UrgencyFlag.urgent(): carries @PropositionQuestion but is not a Jackson property. "
                + "Move the annotation to a record component, field, getter or creator parameter.");
    }

    @Test
    void annotatedDefaultMethodIsReportedOnceForEachInterface() {
        String problem = "EscalationFlag.looksUrgent(): carries @PropositionQuestion but is not a Jackson property. "
            + "Move the annotation to a record component, field, getter or creator parameter.";
        assertOnlyProblem(DefaultMethodNotAProperty.class, problem);
        assertOnlyProblem(FlaggedSub.class, problem);
    }

    @Test
    void annotatedSetterParameterIsRejected() {
        assertOnlyProblem(SetterParameter.class,
            "SetterParameter.urgent (parameter of setUrgent()): carries @PropositionQuestion but is not a Jackson property. "
                + "Move the annotation to a record component, field, getter or creator parameter.");
    }

    @Test
    void propertyJacksonCannotSetIsRejected() {
        assertOnlyProblem(NoMutator.class,
            "NoMutator.urgent: carries @PropositionQuestion but Jackson has no creator parameter, setter or field "
                + "to set it. Add one of these members for the property.");
    }

    @Test
    void choiceOnStringIsRejected() {
        assertOnlyProblem(StringChoice.class,
            "StringChoice.team: @ChoiceQuestion needs an enum type, found java.lang.String. Declare the options as an enum.");
    }

    @Test
    void propositionOnIntIsRejected() {
        assertOnlyProblem(IntProposition.class,
            "IntProposition.urgent: @PropositionQuestion needs boolean or Boolean, found int. "
                + "Declare the property as boolean or Boolean.");
    }

    @Test
    void ratingOnIntegerIsRejected() {
        assertOnlyProblem(IntegerRating.class,
            "IntegerRating.severity: @RatingQuestion needs an enum type, found java.lang.Integer. "
                + "Declare the levels as an enum, lowest first.");
    }

    @Test
    void typeVariableIsRejected() {
        assertOnlyProblem(Picked.class,
            "Picked.pick: @ChoiceQuestion needs a concrete enum type, found type variable E, which Jackson reads as "
                + "java.lang.Enum. Declare the property with a concrete enum type.");
    }

    @Test
    void twoQuestionAnnotationsOnOnePropertyAreRejected() {
        assertOnlyProblem(TwoKinds.class,
            "TwoKinds.urgent: carries @PropositionQuestion and @ChoiceQuestion. Keep one question annotation on the property.");
    }

    @Test
    void differentAskingValuesOnOnePropertyAreRejected() {
        assertOnlyProblem(AskingDiffers.class,
            "AskingDiffers.urgent: members of the property carry @PropositionQuestion with different asking values "
                + "(\"Does this ticket convey urgency?\", \"Is this urgent?\"). "
                + "Use one asking value on every annotated member of the property.");
    }

    @Test
    void blankAskingIsRejected() {
        assertOnlyProblem(BlankAsking.class,
            "BlankAsking.urgent: @PropositionQuestion has a blank asking value. "
                + "Set asking to the instructions the model receives.");
    }

    @Test
    void choiceConstantWithoutDescriptionIsRejected() {
        assertOnlyProblem(MissingDescription.class,
            "MissingDescription.department: choice option UndescribedDepartment.BILLING has no @Described. "
                + "Add @Described with the option's description to UndescribedDepartment.BILLING.");
    }

    @Test
    void choiceEnumWithoutConstantsIsRejected() {
        assertOnlyProblem(EmptyChoice.class,
            "EmptyChoice.department: NoDepartments has no constants, so the choice has no options. "
                + "Add one constant per option to NoDepartments.");
    }

    @Test
    void ratingEnumWithOneConstantIsRejected() {
        assertOnlyProblem(SingleLevel.class,
            "SingleLevel.severity: OneLevel has 1 constant, and a rating needs at least two levels. "
                + "Add the levels to OneLevel, lowest first.");
    }

    @Test
    void enumWrittenAsIndexIsRejected() {
        ObjectMapper indexed = JsonMapper.builder().enable(EnumFeature.WRITE_ENUMS_USING_INDEX).build();

        AnnotatedDecisionException failure = failure(AnnotatedDecisions.using(indexed), IndexedIds.class);

        assertEquals(List.of(
            "IndexedIds.department: Department.BILLING serializes as 0 under this mapper, and an option id must be a "
                + "JSON string. Use a mapper that writes Department constants as strings, for example with "
                + "EnumFeature.WRITE_ENUMS_USING_INDEX disabled.",
            "IndexedIds.department: Department.TECHNICAL serializes as 1 under this mapper, and an option id must be a "
                + "JSON string. Use a mapper that writes Department constants as strings, for example with "
                + "EnumFeature.WRITE_ENUMS_USING_INDEX disabled."),
            failure.problems());
    }

    @Test
    void idThatReadsBackAsAnotherConstantIsRejected() {
        ObjectMapper lowercase = JsonMapper.builder()
            .enable(EnumFeature.WRITE_ENUMS_TO_LOWERCASE)
            .enable(EnumFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
            .build();

        AnnotatedDecisionException failure = failure(AnnotatedDecisions.using(lowercase), DefaultedIds.class);

        assertEquals(List.of(
                "DefaultedIds.department: option id \"billing\" of DefaultedDepartment.BILLING reads back as "
                    + "DefaultedDepartment.TECHNICAL under this mapper. Give each constant of DefaultedDepartment a "
                    + "distinct serialized form that the mapper reads back as the same constant."),
            failure.problems());
    }

    @Test
    void idThatDoesNotReadBackIsRejectedWithJacksonsReason() {
        AnnotatedDecisionException failure = failure(SharedIds.class);

        assertEquals(2, failure.problems().size());
        for (String constant : List.of("BILLING", "TECHNICAL")) {
            String problem = failure.problems().get(constant.equals("BILLING") ? 0 : 1);
            assertTrue(problem.startsWith("SharedIds.department: option id \"same\" of SharedId." + constant
                + " does not read back under this mapper ("), problem);
            assertTrue(problem.contains("\"same\""), problem);
            assertTrue(problem.endsWith("). Make each constant of SharedId readable from its serialized form."), problem);
        }
        assertInstanceOf(JacksonException.class, failure.getCause());
        assertEquals(1, failure.getSuppressed().length);
    }

    @Test
    void twoMembersJacksonCannotMergeAreRejectedWithJacksonsReason() {
        AnnotatedDecisionException failure = failure(DuplicateName.class);

        assertEquals(1, failure.problems().size());
        String problem = failure.problems().get(0);
        assertTrue(problem.startsWith("DuplicateName: Jackson cannot read the properties of the type ("), problem);
        assertTrue(problem.contains("\"urgent\""), problem);
        assertTrue(problem.contains("DuplicateName#urgent") && problem.contains("DuplicateName#hot"), problem);
        assertTrue(problem.endsWith(
            "). Give each Java member its own property name, or correct the definition the message names."), problem);
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }

    @Test
    void membersMergedUnderOneNameAreRejected() {
        assertOnlyProblem(MergedName.class,
            "MergedName.flag: Jackson merges flag and isHot() into the property \"urgent\". "
                + "Rename the members so they share one Java name, or give each its own property name.");
    }

    @Test
    void typeWithoutQuestionsIsRejected() {
        assertOnlyProblem(NoQuestions.class,
            "NoQuestions: declares no questions. "
                + "Annotate at least one property with @PropositionQuestion, @ChoiceQuestion or @RatingQuestion.");
    }

    @Test
    void everyProblemIsReportedInOneException() {
        assertEquals(List.of(
                "SeveralProblems.urgent: @PropositionQuestion has a blank asking value. "
                    + "Set asking to the instructions the model receives.",
                "SeveralProblems.team: @ChoiceQuestion needs an enum type, found java.lang.String. "
                    + "Declare the options as an enum.",
                "SeveralProblems.severity: OneLevel has 1 constant, and a rating needs at least two levels. "
                    + "Add the levels to OneLevel, lowest first.",
                "SeveralProblems.looksUrgent(): carries @PropositionQuestion but is not a Jackson property. "
                    + "Move the annotation to a record component, field, getter or creator parameter."),
            failure(SeveralProblems.class).problems());
    }

    @Test
    void failedParsesAreNotCached() {
        AnnotatedDecisions decisions = AnnotatedDecisions.using(JsonMapper.builder().build());

        AnnotatedDecisionException first = assertThrows(AnnotatedDecisionException.class, () -> decisions.of(StringChoice.class));
        AnnotatedDecisionException second = assertThrows(AnnotatedDecisionException.class, () -> decisions.of(StringChoice.class));

        assertEquals(first.problems(), second.problems());
        assertNotSame(first, second);
    }
}
