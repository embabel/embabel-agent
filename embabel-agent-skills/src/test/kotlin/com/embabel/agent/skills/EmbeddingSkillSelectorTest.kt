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
package com.embabel.agent.skills

import com.embabel.agent.skills.spec.SkillDefinition
import com.embabel.agent.skills.support.LoadedSkill
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.PricingModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class EmbeddingSkillSelectorTest {

    /** Deterministic stand-in: each text embeds to the axis named by its first word. */
    private class AxisEmbedder(val fail: Boolean = false) : EmbeddingService {
        var calls = 0
        override fun embed(text: String): FloatArray {
            calls++
            if (fail) throw IllegalStateException("embedding backend down")
            val axis = when (text.substringBefore(' ').lowercase()) {
                "contract" -> 0
                "finance" -> 1
                else -> 2
            }
            return FloatArray(3) { if (it == axis) 1f else 0f }
        }

        override fun embed(texts: List<String>): List<FloatArray> = texts.map { embed(it) }
        override val dimensions: Int = 3
        override val name: String = "axis"
        override val provider: String = "test"
        override val pricingModel: PricingModel = PricingModel.ALL_YOU_CAN_EAT
        override fun infoString(verbose: Boolean?, indent: Int): String = "axis"
    }

    private fun skill(name: String, description: String, activation: String? = "embedding") = LoadedSkill(
        skillMetadata = SkillDefinition(
            name = name,
            description = description,
            metadata = activation?.let { mapOf("activation" to it) },
            instructions = "guidance body for $name",
        ),
        basePath = Path.of("/tmp/$name"),
    )

    private val contractSkill = skill("contract-negation", "contract confidentiality carve-outs")
    private val financeSkill = skill("metric-formulas", "finance derived metric formulas")

    @Test
    fun `selects the matching-domain skill and rejects the other`() {
        val selected = EmbeddingSkillSelector(AxisEmbedder())
            .select("contract question about carve-outs", listOf(contractSkill, financeSkill))
        assertEquals(listOf("contract-negation"), selected.map { it.skill.name })
        assertEquals(1.0, selected.single().score, 1e-9)
    }

    @Test
    fun `a skill that does not opt in is never selected — existing libraries are unaffected`() {
        val optedOut = skill("contract-negation", "contract confidentiality carve-outs", activation = null)
        val selected = EmbeddingSkillSelector(AxisEmbedder()).select("contract question", listOf(optedOut))
        assertTrue(selected.isEmpty())
    }

    @Test
    fun `an unrelated query clears no skill`() {
        val selected = EmbeddingSkillSelector(AxisEmbedder())
            .select("weather forecast for tomorrow", listOf(contractSkill, financeSkill))
        assertTrue(selected.isEmpty(), "orthogonal query must score 0, below threshold")
    }

    @Test
    fun `at most maxSkills are returned, best first`() {
        val alsoContract = skill("contract-terms", "contract duration and termination")
        val selected = EmbeddingSkillSelector(AxisEmbedder(), maxSkills = 1)
            .select("contract question", listOf(contractSkill, alsoContract))
        assertEquals(1, selected.size)
    }

    @Test
    fun `description embeddings are cached across calls`() {
        val embedder = AxisEmbedder()
        val selector = EmbeddingSkillSelector(embedder)
        selector.select("contract one", listOf(contractSkill, financeSkill))
        val afterFirst = embedder.calls
        selector.select("contract two", listOf(contractSkill, financeSkill))
        // Second call embeds only the query: both skill descriptions are cached.
        assertEquals(afterFirst + 1, embedder.calls)
    }

    @Test
    fun `contributorFor defers selection until the prompt is assembled`() {
        val embedder = AxisEmbedder()
        val contributor = EmbeddingSkillSelector(embedder)
            .contributorFor("contract question", listOf(contractSkill, financeSkill))
        assertEquals(0, embedder.calls, "configuring a runner must not embed anything")
        val contribution = contributor.contribution()
        assertTrue(contribution.contains("## contract-negation"), contribution)
        assertTrue(contribution.contains("guidance body for contract-negation"), contribution)
        assertTrue(embedder.calls > 0, "assembly embeds")
    }

    @Test
    fun `a contributor with no match contributes nothing`() {
        val contributor = EmbeddingSkillSelector(AxisEmbedder())
            .contributorFor("weather tomorrow", listOf(contractSkill))
        assertTrue(contributor.contribution().isEmpty())
    }

    @Test
    fun `the contribution carries the guidance role`() {
        val contributor = EmbeddingSkillSelector(AxisEmbedder())
            .contributorFor("contract question", listOf(contractSkill))
        assertEquals(EmbeddingSkillSelector.GUIDANCE_ROLE, contributor.promptContribution().role)
    }

    @Test
    fun `an embedding failure yields no skills rather than breaking the caller`() {
        val selected = EmbeddingSkillSelector(AxisEmbedder(fail = true))
            .select("contract question", listOf(contractSkill))
        assertTrue(selected.isEmpty())
    }
}
