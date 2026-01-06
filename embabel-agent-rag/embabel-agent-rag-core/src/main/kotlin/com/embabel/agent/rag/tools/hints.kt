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
package com.embabel.agent.rag.tools

import com.embabel.common.ai.prompt.PromptContributor

/**
 * Hypothetical Document Embedding hint
 * Used to generate a synthetic document for embedding to use a vector search query.
 * The hypothetical document will be embedded
 * @param context the context to use for generating the synthetic document.
 * The conversation and prompt context will be used if this is not provided.
 * @param maxWords the number of words to generate for the synthetic document (default is 50)
 * what the answer should relate to.
 * For example: "The history of the Roman Empire."
 */
data class TryHyDE @JvmOverloads constructor(
    val context: String? = null,
    val maxWords: Int = 50,
) : PromptContributor {

    override fun contribution(): String {
        return """
            If you're having a problem with vector search result relevance, try generating a hypothetical document
            to use as the query.
            Use at most $maxWords words to generate a hypothetical answer
            ${context?.let { "based on the following context:\n${it}" } ?: "Based on the current conversation context."}
           """.trimIndent()
    }

    fun withMaxWords(wordCount: Int): TryHyDE =
        copy(maxWords = wordCount)

    companion object {

        /**
         * Tell the LLM to generate any HyDE queries based on the current conversation context
         */
        @JvmStatic
        fun usingConversationContext(): TryHyDE = TryHyDE()

        @JvmStatic
        fun withContext(context: String): TryHyDE = TryHyDE(context)
    }
}
