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

import java.time.Duration

/**
 * Implemented by types that add hints to RAG requests.
 * There should be one hint type per class.
 * Examples include HyDE, result compression and DesiredMaxLatency
 */
interface RagHint

open class ResultCompression(
    val enabled: Boolean = true,
) : RagHint

/**
 * Desired maximum latency for RAG retrieval
 * @param duration the desired maximum latency
 */
data class DesiredMaxLatency(
    val duration: Duration,
) : RagHint {

    companion object {
        val UNBOUNDED = DesiredMaxLatency(Duration.ofMillis(Long.MAX_VALUE))

        @JvmStatic
        fun of(duration: Duration): DesiredMaxLatency = DesiredMaxLatency(duration)
    }
}

/**
 * Hypothetical Document Embedding
 * Used to generate a synthetic document for embedding from the query.
 * @param context the context to use for generating the synthetic document:
 * @param wordCount the number of words to generate for the synthetic document (default is 50)
 * what the answer should relate to.
 * For example: "The history of the Roman Empire."
 */
data class HyDE @JvmOverloads constructor(
    val context: String,
    val wordCount: Int = 50,
) : RagHint
