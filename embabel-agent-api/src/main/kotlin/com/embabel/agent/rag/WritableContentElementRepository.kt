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
package com.embabel.agent.rag

import com.embabel.agent.rag.ingestion.ContentElementRepository

/**
 * WritableRagService that also allows us to load and save ContentElements.
 */
interface WritableContentElementRepository : WritableStore, ContentElementRepository {

    /**
     * Provision this rag service if necessary
     */
    fun provision() {
        // Default no-op
    }

    /**
     * List of enhancers
     */
    val enhancers: List<RetrievableEnhancer>

    fun <T : Retrievable> enhance(retrievable: T): T {
        // TODO need context to do this properly
        var enhanced = retrievable
        for (enhancer in enhancers) {
            enhanced = enhancer.enhance(retrievable)
        }
        return enhanced
    }

    /**
     * Retrievables have been saved to the store,
     * but Retrievables are special, and we probably want to embed them
     */
    fun onNewRetrievables(
        retrievables: List<Retrievable>,
    )

}
