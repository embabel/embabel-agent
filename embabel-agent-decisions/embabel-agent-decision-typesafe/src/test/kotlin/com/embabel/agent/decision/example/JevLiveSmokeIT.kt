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

import com.embabel.agent.decision.api.EvidenceKind
import com.embabel.agent.decision.api.typesafe.TypeSafeDecisionModel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.function.Supplier

internal fun liveSmokeEnabled(env: Map<String, String>, properties: Map<String, String>): Boolean =
    properties["decision.integration-profile"] == "true" &&
        properties["decision.integration-profile-owned"] == "true" &&
        properties["decision.live"] == "true" &&
        !env["TYPESAFE_API_KEY"].isNullOrBlank() &&
        !env["TYPESAFE_MODEL"].isNullOrBlank() &&
        (env["CI"] == null || env["CI"].equals("false", ignoreCase = true))

internal fun runAuthorizedLiveSmoke(
    env: Map<String, String>,
    properties: Map<String, String>,
    action: () -> Unit,
): Boolean {
    if (!liveSmokeEnabled(env, properties)) return false
    action()
    return true
}

class JevLiveSmokeIT {
    @Test
    fun `integration profile owns the authorization marker`() {
        assertThat(System.getProperty("decision.integration-profile")).isEqualTo("true")
        assertThat(System.getProperty("decision.integration-profile-owned")).isEqualTo("true")
    }

    @Test
    fun `live guard requires opt in credentials model and a non CI process`() {
        val enabledEnv = mapOf("TYPESAFE_API_KEY" to "present", "TYPESAFE_MODEL" to "requested")
        val enabledProperties = mapOf(
            "decision.integration-profile" to "true",
            "decision.integration-profile-owned" to "true",
            "decision.live" to "true",
        )
        assertThat(liveSmokeEnabled(enabledEnv, enabledProperties)).isTrue()
        assertThat(liveSmokeEnabled(enabledEnv + ("CI" to "false"), enabledProperties)).isTrue()
        assertThat(liveSmokeEnabled(enabledEnv + ("CI" to "FALSE"), enabledProperties)).isTrue()
        listOf("", "1", "yes", "true", "TRUE").forEach { ci ->
            assertThat(liveSmokeEnabled(enabledEnv + ("CI" to ci), enabledProperties)).isFalse()
        }
        assertThat(liveSmokeEnabled(enabledEnv, enabledProperties - "decision.integration-profile")).isFalse()
        assertThat(liveSmokeEnabled(enabledEnv, enabledProperties + ("decision.integration-profile" to "false"))).isFalse()
        assertThat(liveSmokeEnabled(enabledEnv, enabledProperties - "decision.integration-profile-owned")).isFalse()
        assertThat(liveSmokeEnabled(enabledEnv, enabledProperties + ("decision.integration-profile-owned" to "false"))).isFalse()
        assertThat(liveSmokeEnabled(enabledEnv, enabledProperties - "decision.live")).isFalse()
        assertThat(liveSmokeEnabled(enabledEnv, enabledProperties + ("decision.live" to "false"))).isFalse()
        assertThat(liveSmokeEnabled(enabledEnv - "TYPESAFE_API_KEY", enabledProperties)).isFalse()
        assertThat(liveSmokeEnabled(enabledEnv + ("TYPESAFE_API_KEY" to " "), enabledProperties)).isFalse()
        assertThat(liveSmokeEnabled(enabledEnv - "TYPESAFE_MODEL", enabledProperties)).isFalse()
        assertThat(liveSmokeEnabled(enabledEnv + ("TYPESAFE_MODEL" to " "), enabledProperties)).isFalse()
    }

    @Test
    fun `direct test selection without integration marker cannot construct transport`() {
        val env = mapOf("TYPESAFE_API_KEY" to "present", "TYPESAFE_MODEL" to "requested")
        var networkConstructions = 0
        val authorized = runAuthorizedLiveSmoke(env, mapOf("decision.live" to "true")) {
            networkConstructions++
        }
        assertThat(authorized).isFalse()
        assertThat(networkConstructions).isZero()
    }

    @Test
    fun `both public consumers run against live Jev`() {
        val env = System.getenv().toMap()
        val properties = System.getProperties().stringPropertyNames().associateWith(System::getProperty)
        val authorized = runAuthorizedLiveSmoke(env, properties) {
            val model = TypeSafeDecisionModel.create(Supplier { env.getValue("TYPESAFE_API_KEY") }, env.getValue("TYPESAFE_MODEL"))
                .withDefaults(Duration.ofSeconds(30), com.embabel.agent.decision.api.DecisionRecordPolicy.metadata())
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
        assumeTrue(authorized, "live Jev smoke requires the integration profile marker and explicit opt-in")
    }
}
