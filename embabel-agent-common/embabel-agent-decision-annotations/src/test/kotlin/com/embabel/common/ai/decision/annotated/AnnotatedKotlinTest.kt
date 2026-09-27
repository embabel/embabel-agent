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

import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.RatingLevel
import com.embabel.common.ai.decision.RatingQuestionSpec
import com.embabel.common.ai.decision.decisionSpec
import com.embabel.common.ai.decision.annotated.AnnotatedSpecParityTest.Department
import com.embabel.common.ai.decision.annotated.AnnotatedSpecParityTest.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

private const val URGENT = "Does this ticket convey urgency?"
private const val TEAM = "Which team should handle this?"
private const val SEVERITY = "How severe is the impact?"

// Observed with Kotlin 2.2.21 and jackson-module-kotlin 3.1.x, with no -Xannotation-default-target flag.
// The question annotations target FIELD, METHOD and PARAMETER and have no Kotlin PROPERTY target, so
// @property: does not compile. On a data class only @get: reads.
// - No use-site target, and @param:, put the annotation on the constructor parameter. A data class
//   also copies it to the matching parameter of copy(). Jackson does not treat copy() as a creator,
//   so the parser reports each copy() parameter as an annotation outside any property.
// - @field: puts the annotation on the private backing field. Jackson drops a private field from a
//   property that a creator parameter sets, so the parser reports the field the same way.
// - @get: puts the annotation on the getter, which is part of the property, and the spec equals the
//   one read from the Java record.
// On a plain class, which has no copy(), the default site reads.

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

/**
 * Checks the Kotlin DSL against the annotated Java record, and records where Kotlin places the
 * question annotations on a data class and which placements Jackson reads.
 */
class AnnotatedKotlinTest {

    private val kotlinMapper = JsonMapper.builder().addModule(kotlinModule()).build()

    private val javaTriage: DecisionSpec = AnnotatedDecisions.defaults().of(AnnotatedSpecParityTest.Triage::class.java).spec()

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
        assertEquals(dsl.definitionId, javaTriage.definitionId)
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
    fun `default site and param site fail on a data class because of copy`() {
        val decisions = AnnotatedDecisions.using(kotlinMapper)

        for (type in listOf(DefaultSiteTriage::class.java, ParamSiteTriage::class.java)) {
            assertEquals(notAProperty(type, " (parameter of copy())"), problemsOf(decisions, type), type.simpleName)
        }
    }

    @Test
    fun `field site fails on a data class because Jackson drops the private field`() {
        val decisions = AnnotatedDecisions.using(kotlinMapper)

        assertEquals(notAProperty(FieldSiteTriage::class.java, ""), problemsOf(decisions, FieldSiteTriage::class.java))
    }

    @Test
    fun `getter site and a plain class read to the Java record spec`() {
        val decisions = AnnotatedDecisions.using(kotlinMapper)

        for (type in listOf(GetterSiteTriage::class.java, PlainClassTriage::class.java)) {
            assertEquals(javaTriage, decisions.of(type).spec(), type.simpleName)
            assertEquals(javaTriage.definitionId, decisions.of(type).spec().definitionId, type.simpleName)
        }
    }

    private fun problemsOf(decisions: AnnotatedDecisions, type: Class<*>): List<String> =
        assertThrows(AnnotatedDecisionException::class.java) { decisions.of(type) }.problems()

    private fun notAProperty(type: Class<*>, member: String): List<String> {
        val fix = " but is not a Jackson property. Move the annotation to a record component, field, getter or creator parameter."
        val name = type.simpleName
        return listOf(
            "$name.urgent$member: carries @PropositionQuestion$fix",
            "$name.department$member: carries @ChoiceQuestion$fix",
            "$name.severity$member: carries @RatingQuestion$fix",
        )
    }

    @Test
    fun `described on a Kotlin enum entry sets the level description`() {
        val severity = AnnotatedDecisions.using(kotlinMapper).of(KotlinEnumTriage::class.java)
            .spec().question("severity") as RatingQuestionSpec

        assertEquals(listOf(RatingLevel("LOW"), RatingLevel("HIGH", "Work is blocked for one customer")), severity.levels)
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
