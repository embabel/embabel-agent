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

import com.embabel.agent.skills.support.LoadedSkill
import com.embabel.common.ai.model.EmbeddingService
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * A skill chosen by similarity to a query, with the score that chose it.
 */
data class RetrievedSkill(
    val skill: LoadedSkill,
    val score: Double,
)

/**
 * Chooses skills by SEMANTIC SIMILARITY, for callers that want to inject a skill's
 * instructions into a prompt rather than offer the skill to the model as a tool.
 *
 * The existing activation path ([Skills.asIndividualReferences]) is a decision the LLM
 * makes: it reads skill descriptions, picks one, calls it, then reads the body. That
 * costs a turn and a judgement — and the judgement is exactly what a small model gets
 * wrong, because a model confident in a wrong answer never reaches for help. Selecting
 * by embedding removes the decision: the skill whose description matches the query
 * arrives already open, at the cost of one embedding call and no LLM work.
 *
 * OPT-IN AND ADDITIVE. Nothing selects this way unless a caller constructs this class,
 * and only skills that ASK for it ([activatesByEmbedding]) are eligible, so an existing
 * skill library behaves exactly as before.
 *
 * Similarity discriminates by SUBJECT, not by difficulty: it can tell a contract question
 * from a financial one, but not a hard question from an easy one. A skill selected this
 * way must therefore be safe to inject on every question in its subject area, since that
 * is what will happen.
 */
class EmbeddingSkillSelector @JvmOverloads constructor(
    private val embeddingService: EmbeddingService,
    private val threshold: Double = DEFAULT_THRESHOLD,
    private val maxSkills: Int = DEFAULT_MAX_SKILLS,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Skill description embeddings, keyed by the description itself so an edited skill
     * re-embeds and an unchanged one never does. Bounded by the skill library's size.
     */
    private val cache = ConcurrentHashMap<String, FloatArray>()

    /**
     * The eligible skills whose descriptions match [query], best first, at most
     * [maxSkills]. Empty when nothing clears [threshold].
     *
     * Fail-open: an embedding failure yields no skills and is logged, because guidance
     * is an enhancement and must never be the reason an answer does not happen.
     */
    fun select(query: String, skills: List<LoadedSkill>): List<RetrievedSkill> {
        val eligible = skills.filter { it.activatesByEmbedding() }
        if (eligible.isEmpty() || query.isBlank()) return emptyList()
        return try {
            val queryVector = embeddingService.embed(query)
            eligible
                .map { RetrievedSkill(it, cosine(queryVector, vectorFor(it))) }
                .filter { it.score >= threshold }
                .sortedByDescending { it.score }
                .take(maxSkills)
                .also { selected ->
                    if (selected.isNotEmpty()) {
                        logger.debug(
                            "Selected {} skill(s) by embedding for '{}': {}",
                            selected.size, query.take(60),
                            selected.joinToString { "${it.skill.name}=${"%.3f".format(it.score)}" },
                        )
                    }
                }
        } catch (e: Exception) {
            logger.warn("Embedding skill selection failed, continuing without: {}", e.message)
            emptyList()
        }
    }

    private fun vectorFor(skill: LoadedSkill): FloatArray =
        cache.computeIfAbsent(skill.description) { embeddingService.embed(it) }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / (Math.sqrt(na) * Math.sqrt(nb))
    }

    companion object {

        /**
         * Frontmatter key, inside the spec's open `metadata` map, by which a skill asks to
         * be selected by embedding. Unknown metadata keys are ignored by other Agent Skills
         * runtimes, so a skill carrying this stays portable.
         */
        const val ACTIVATION_KEY = "activation"

        /** [ACTIVATION_KEY] value that opts a skill into embedding selection. */
        const val ACTIVATION_EMBEDDING = "embedding"

        /**
         * Sits between the similarity a description shows for questions in its own subject
         * area and the similarity it shows for other subjects. Tune per embedding model:
         * scores are not calibrated across them.
         */
        const val DEFAULT_THRESHOLD = 0.30

        /** Injected guidance competes with retrieved evidence for the model's attention. */
        const val DEFAULT_MAX_SKILLS = 2
    }
}

/**
 * Does this skill ask to be selected by embedding similarity and injected, rather than
 * offered to the model as a callable tool? False unless the skill says so, so existing
 * skills are unaffected.
 */
fun LoadedSkill.activatesByEmbedding(): Boolean =
    metadata?.get(EmbeddingSkillSelector.ACTIVATION_KEY)
        ?.equals(EmbeddingSkillSelector.ACTIVATION_EMBEDDING, ignoreCase = true) == true
