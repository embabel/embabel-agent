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
package com.embabel.common.ai.decision.annotated

import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.ClassificationSpec
import com.embabel.common.ai.classification.MappedClassificationResult
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.classification.classificationSpec
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.RatingLevel
import com.embabel.common.ai.decision.RatingQuestionSpec
import com.embabel.common.ai.decision.RatingResult
import com.embabel.common.ai.decision.decisionSpec
import com.embabel.common.ai.decision.support.StubDecisionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

private const val URGENT = "Does this ticket convey urgency?"
private const val TEAM = "Which team should handle this?"
private const val SEVERITY = "How severe is the impact?"

// The same constants and descriptions as the enums of the Java record in AnnotatedSpecParityTest.
private enum class Department {
    @Described("Payments, invoicing, refunds")
    BILLING,

    @Described("Bugs, outages, integrations")
    TECHNICAL,
}

private enum class Severity {
    LOW,

    @Described("Work is blocked for one customer")
    HIGH,

    @Described("Work is blocked for many customers")
    CRITICAL,
}

// Observed with Kotlin 2.2.21 and jackson-module-kotlin 3.1.x, with no -Xannotation-default-target flag.
// The question annotations target FIELD, METHOD and PARAMETER and have no Kotlin PROPERTY target, so
// @property: does not compile.
// - No use-site target, and @param:, put the annotation on the constructor parameter. A data class
//   also copies it to the matching parameter of copy(). The parser skips a copy() parameter that
//   carries the same question annotation as the constructor parameter, so both placements read.
//   The componentN() methods carry no annotation.
// - @field: puts the annotation on the private backing field. Jackson drops a private field from a
//   property that a creator parameter sets, so the parser reports the field and names the members
//   Jackson reads for the property.
// - @get: puts the annotation on the getter, which is part of the property.
// Every placement that reads gives the spec read from the Java record.

// tag::annotated-kotlin[]
private data class DefaultSiteTriage(
    @PropositionQuestion(asking = URGENT) val urgent: Boolean,
    @ChoiceQuestion(asking = TEAM) val department: Department,
    @RatingQuestion(asking = SEVERITY) val severity: Severity,
)

private data class ParamSiteTriage(
    @param:PropositionQuestion(asking = URGENT) val urgent: Boolean,
    @param:ChoiceQuestion(asking = TEAM) val department: Department,
    @param:RatingQuestion(asking = SEVERITY) val severity: Severity,
)

private data class FieldSiteTriage(
    @field:PropositionQuestion(asking = URGENT) val urgent: Boolean,
    @field:ChoiceQuestion(asking = TEAM) val department: Department,
    @field:RatingQuestion(asking = SEVERITY) val severity: Severity,
)

private data class GetterSiteTriage(
    @get:PropositionQuestion(asking = URGENT) val urgent: Boolean,
    @get:ChoiceQuestion(asking = TEAM) val department: Department,
    @get:RatingQuestion(asking = SEVERITY) val severity: Severity,
)
// end::annotated-kotlin[]

// Kotlin 2.2 warns that a later default site also annotates the backing field. Writing both targets
// gives that placement today.
private data class ParamAndFieldSiteTriage(
    @param:PropositionQuestion(asking = URGENT) @field:PropositionQuestion(asking = URGENT) val urgent: Boolean,
    @param:ChoiceQuestion(asking = TEAM) @field:ChoiceQuestion(asking = TEAM) val department: Department,
    @param:RatingQuestion(asking = SEVERITY) @field:RatingQuestion(asking = SEVERITY) val severity: Severity,
)

private class PlainClassTriage(
    @PropositionQuestion(asking = URGENT) val urgent: Boolean,
    @ChoiceQuestion(asking = TEAM) val department: Department,
    @RatingQuestion(asking = SEVERITY) val severity: Severity,
)

private enum class KotlinSeverity {
    LOW,

    @Described("Work is blocked for one customer")
    HIGH,
}

private data class KotlinEnumTriage(
    @get:RatingQuestion(asking = SEVERITY) val severity: KotlinSeverity,
)

@Classification(asking = TEAM)
private enum class RoutedDepartment {
    @Described("Payments, invoicing, refunds")
    BILLING,

    @Described("Bugs, outages, integrations")
    TECHNICAL,
}

@Classification(asking = TEAM)
private enum class UndescribedDepartment {
    BILLING,
}

private data class KotlinRouting(
    @ChoiceQuestion(asking = TEAM) val department: Department,
)

/**
 * Checks the Kotlin DSL against the annotated Java record, and records where Kotlin places the
 * question annotations on a data class and which placements Jackson reads.
 */
class AnnotatedKotlinTest {

    @Test
    fun `defaults reads and projects Kotlin while requiring explicit identity values`() {
        val decision = AnnotatedDecisions.defaults().of(KotlinIdentity::class.java)
        val response = StubDecisionService.builder("stub")
            .proposition("urgent", PropositionResult.Answered(true, ModelProvenance("model", "provider")))
            .build().ask("ticket", decision.spec())
        val projection = decision.project(response, mapOf("revision" to 9))
        assertEquals(KotlinIdentity(true, 9), projection.value)
        assertEquals(response, projection.response)
        assertThrows(com.embabel.common.ai.decision.DecisionProjectionException::class.java) {
            decision.project(response)
        }
    }

    private val kotlinMapper = JsonMapper.builder().addModule(kotlinModule()).build()

    // Kotlin test sources compile before Java test sources, so the Java record is loaded by name.
    private val javaTriage: DecisionSpec = AnnotatedDecisions.defaults()
        .of(Class.forName("com.embabel.common.ai.decision.annotated.AnnotatedSpecParityTest\$Triage"))
        .spec()

    @Test
    fun `DSL spec equals the annotated Java record spec`() {
        val dsl = decisionSpec {
            proposition("urgent") { asking(URGENT) }
            choice("department") {
                asking(TEAM)
                option("BILLING", "Payments, invoicing, refunds")
                option("TECHNICAL", "Bugs, outages, integrations")
            }
            rating("severity") {
                asking(SEVERITY)
                level("LOW")
                level("HIGH", "Work is blocked for one customer")
                level("CRITICAL", "Work is blocked for many customers")
            }
        }

        assertEquals(dsl, javaTriage)
    }

    @Test
    fun `default site annotates the constructor parameter and the copy parameter`() {
        assertEquals(
            setOf("copy parameter", "parameter"),
            placements(DefaultSiteTriage::class.java, "urgent", PropositionQuestion::class.java),
        )
        assertEquals(
            setOf("copy parameter", "parameter"),
            placements(DefaultSiteTriage::class.java, "department", ChoiceQuestion::class.java),
        )
        assertEquals(
            setOf("copy parameter", "parameter"),
            placements(DefaultSiteTriage::class.java, "severity", RatingQuestion::class.java),
        )
        assertEquals(setOf("parameter"), placements(PlainClassTriage::class.java, "urgent", PropositionQuestion::class.java))
        assertTrue(
            DefaultSiteTriage::class.java.declaredMethods
                .filter { it.name.startsWith("component") }
                .all { it.annotations.isEmpty() },
        )
    }

    @Test
    fun `use-site targets annotate the named member`() {
        assertEquals(
            setOf("copy parameter", "parameter"),
            placements(ParamSiteTriage::class.java, "urgent", PropositionQuestion::class.java),
        )
        assertEquals(setOf("field"), placements(FieldSiteTriage::class.java, "urgent", PropositionQuestion::class.java))
        assertEquals(setOf("getter"), placements(GetterSiteTriage::class.java, "urgent", PropositionQuestion::class.java))
    }

    @Test
    fun `field site fails on a data class and names the members Jackson reads`() {
        val decisions = AnnotatedDecisions.using(kotlinMapper)

        assertEquals(fieldProblems(FieldSiteTriage::class.java), problemsOf(decisions, FieldSiteTriage::class.java))
    }

    private fun fieldProblems(type: Class<*>): List<String> {
        val name = type.simpleName
        val fix = " In Kotlin, write the annotation with @get: or with no use-site target."
        return listOf(
            "$name.urgent: carries @PropositionQuestion but Jackson leaves this field out of the property \"urgent\". " +
                "Move the annotation to creator parameter urgent or getter getUrgent().$fix",
            "$name.department: carries @ChoiceQuestion but Jackson leaves this field out of the property \"department\". " +
                "Move the annotation to creator parameter department or getter getDepartment().$fix",
            "$name.severity: carries @RatingQuestion but Jackson leaves this field out of the property \"severity\". " +
                "Move the annotation to creator parameter severity or getter getSeverity().$fix",
        )
    }

    @Test
    fun `default, param, param and field, and getter sites and a plain class read to the Java record spec`() {
        val decisions = AnnotatedDecisions.using(kotlinMapper)

        for (type in listOf(
            DefaultSiteTriage::class.java,
            ParamSiteTriage::class.java,
            ParamAndFieldSiteTriage::class.java,
            GetterSiteTriage::class.java,
            PlainClassTriage::class.java,
        )) {
            assertEquals(javaTriage, decisions.of(type).spec(), type.simpleName)
        }
    }

    @Test
    fun `default site on a data class projects an answered response`() {
        val decision = AnnotatedDecisions.using(kotlinMapper).of(DefaultSiteTriage::class.java)
        val provenance = ModelProvenance("stub-model", "stub", "1", "req-1")
        val response = StubDecisionService.builder("triage-stub")
            .proposition("urgent", PropositionResult.Answered(true, provenance))
            .choice("department", ClassificationResult.Selected("TECHNICAL", provenance))
            .rating("severity", RatingResult.Answered(provenance, "HIGH"))
            .build()
            .ask("The export has failed for every customer since this morning.", decision.spec())

        assertEquals(DefaultSiteTriage(true, Department.TECHNICAL, Severity.HIGH), decision.project(response).value)
    }

    private fun problemsOf(decisions: AnnotatedDecisions, type: Class<*>): List<String> =
        assertThrows(AnnotatedDecisionException::class.java) { decisions.of(type) }.problems()

    @Test
    fun `described on a Kotlin enum entry sets the level description`() {
        val severity = AnnotatedDecisions.using(kotlinMapper).of(KotlinEnumTriage::class.java)
            .spec().question("severity") as RatingQuestionSpec

        assertEquals(listOf(RatingLevel("LOW"), RatingLevel("HIGH", "Work is blocked for one customer")), severity.levels)
    }

    @Test
    fun `classification reads a Kotlin enum and maps a selection to its entry`() {
        val departments = AnnotatedDecisions.classification(RoutedDepartment::class.java)
        val selected = ClassificationResult.Selected("TECHNICAL", ModelProvenance("stub-model", "stub"))
        val expected = classificationSpec {
            asking(TEAM)
            category("BILLING", "Payments, invoicing, refunds")
            category("TECHNICAL", "Bugs, outages, integrations")
        }

        assertEquals(expected, departments.spec())
        assertEquals(MappedClassificationResult.Selected(RoutedDepartment.TECHNICAL, selected), departments.map(selected))
    }

    @Test
    fun `classification rejects a Kotlin enum entry without Described`() {
        val failure = assertThrows(AnnotatedDecisionException::class.java) {
            AnnotatedDecisions.classification(UndescribedDepartment::class.java)
        }

        assertEquals(
            listOf(
                "UndescribedDepartment.BILLING: category has no @Described. " +
                    "Add @Described with the category's description to UndescribedDepartment.BILLING.",
            ),
            failure.problems(),
        )
    }

    @Test
    fun `data class with one choice question reads as a classification spec and projects`() {
        val decision = AnnotatedDecisions.using(kotlinMapper).of(KotlinRouting::class.java)
        val response = StubDecisionService.builder("routing-stub")
            .choice("department", ClassificationResult.Selected("BILLING", ModelProvenance("stub-model", "stub")))
            .build()
            .ask("My card was charged twice.", decision.spec())

        assertEquals("department", assertInstanceOf(ClassificationSpec::class.java, decision.spec()).question.name)
        assertEquals(KotlinRouting(Department.BILLING), decision.project(response).value)
    }

    // Where Kotlin put the annotation for one property: the primary constructor parameter, the
    // backing field, the getter, the parameter of a data class copy(), or several of these.
    private fun placements(type: Class<*>, property: String, annotation: Class<out Annotation>): Set<String> {
        val found = sortedSetOf<String>()
        if (type.declaredFields.any { it.name == property && it.isAnnotationPresent(annotation) }) {
            found += "field"
        }
        val getter = "get" + property.replaceFirstChar { it.uppercase() }
        if (type.declaredMethods.any { it.name == getter && it.isAnnotationPresent(annotation) }) {
            found += "getter"
        }
        val constructor = type.declaredConstructors.filterNot { it.isSynthetic }.maxBy { it.parameterCount }
        if (constructor.parameters.any { it.name == property && it.isAnnotationPresent(annotation) }) {
            found += "parameter"
        }
        val copy = type.declaredMethods.filter { it.name == "copy" }.flatMap { it.parameters.asList() }
        if (copy.any { it.name == property && it.isAnnotationPresent(annotation) }) {
            found += "copy parameter"
        }
        return found
    }
}

private data class KotlinIdentity(
    @PropositionQuestion(asking = "Urgent?") val urgent: Boolean,
    val revision: Int = 7,
)
