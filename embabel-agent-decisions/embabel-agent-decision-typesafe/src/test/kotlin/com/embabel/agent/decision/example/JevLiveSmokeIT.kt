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
package com.embabel.agent.decision.example

import com.embabel.agent.decision.EvidenceKind
import com.embabel.agent.decision.typesafe.TypeSafeDecisionModel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.function.Supplier

internal fun liveSmokeEnabled(env: Map<String, String>, properties: Map<String, String>): Boolean =
    properties["decision.live"] == "true" &&
        !env["TYPESAFE_API_KEY"].isNullOrBlank() &&
        !env["TYPESAFE_MODEL"].isNullOrBlank() &&
        !env["CI"].equals("true", ignoreCase = true)

class JevLiveSmokeIT {
    @Test
    fun `live guard requires opt in credentials model and a non CI process`() {
        val enabledEnv = mapOf("TYPESAFE_API_KEY" to "present", "TYPESAFE_MODEL" to "requested")
        assertThat(liveSmokeEnabled(enabledEnv, mapOf("decision.live" to "true"))).isTrue()
        assertThat(liveSmokeEnabled(enabledEnv + ("CI" to "true"), mapOf("decision.live" to "true"))).isFalse()
        assertThat(liveSmokeEnabled(enabledEnv, mapOf("decision.live" to "false"))).isFalse()
        assertThat(liveSmokeEnabled(enabledEnv - "TYPESAFE_API_KEY", mapOf("decision.live" to "true"))).isFalse()
        assertThat(liveSmokeEnabled(enabledEnv - "TYPESAFE_MODEL", mapOf("decision.live" to "true"))).isFalse()
    }

    @Test
    fun `both public consumers run against live Jev`() {
        val env = System.getenv().toMap()
        val properties = System.getProperties().stringPropertyNames().associateWith(System::getProperty)
        assumeTrue(liveSmokeEnabled(env, properties), "live Jev smoke is explicitly opt-in")
        val model = TypeSafeDecisionModel.create(Supplier { env.getValue("TYPESAFE_API_KEY") }, env.getValue("TYPESAFE_MODEL"))
            .withDefaults(Duration.ofSeconds(30), com.embabel.agent.decision.DecisionRecordPolicy.metadata())
        val kotlin = runKotlinDecision(model)
        val javaEvidence = JevDecisionExample.run(model)
        listOf(kotlin.provenance, javaEvidence.provenance()).forEach { provenance ->
            assertThat(provenance.evidenceKind).isEqualTo(EvidenceKind.DISTRIBUTION)
            assertThat(provenance.resolvedModel).isNotBlank()
        }
        assertThat(kotlin.routeDistribution.values).allMatch { it.isFinite() && it in 0.0..1.0 }
        assertThat(javaEvidence.routeDistribution().values).allMatch { it.isFinite() && it in 0.0..1.0 }
        System.err.println("Live decision smoke passed at ${java.time.Instant.now()} with resolved model ${javaEvidence.provenance().resolvedModel}")
    }
}
