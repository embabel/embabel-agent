/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.rag.service

import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.EntityData
import com.embabel.agent.rag.model.Fact

/**
 * Implemented by classes that can format RagResponse objects into a string
 * for inclusion in tool responses or prompts.
 */
fun interface RagResponseFormatter {

    /**
     * Formats the given RagResponse into a string for inclusion in tool responses or prompts.
     * @param ragResponse The RagResponse to format.
     * @return A string representation of the RagResponse.
     */
    fun format(ragResponse: RagResponse): String
}

/**
 * Sensible default RagResponseFormatter
 */
object SimpleRagResponseFormatter : RagResponseFormatter {

    const val NO_RESULTS_FOUND = "No results found"

    override fun format(ragResponse: RagResponse): String {
        val results = ragResponse.results
        return if (results.isEmpty()) {
            NO_RESULTS_FOUND
        } else {
            results.joinToString(separator = "\n\n") { result ->
                val formattedScore = "%.2f".format(result.score)

                when (val match = result.match) {
                    is EntityData -> {
                        "$formattedScore: ${match.embeddableValue()}"
                    }

                    is Chunk -> {
                        "$formattedScore: ${match.text}"
                    }

                    is Fact -> {
                        "$formattedScore: fact - ${match.assertion}"
                    }

                    else -> {
                        "$formattedScore: ${result.match.javaClass.simpleName} - ${match.infoString(verbose = true)}"
                    }
                }
            }
        }
    }
}
