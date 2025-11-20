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

import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.event.RagEventListener

/**
 * Given a RAG service, create an enhanced RAG service for use in a particular operation.
 */
interface RagServiceEnhancer {

    /**
     * Create a new Rag Service for use in a given operation
     * @param operationContext context of the operation for which the RAG service is being created.
     * Having the context allows for running LLM operations such as summarization
     * and considering the current AgentProcess.
     */
    fun create(
        operationContext: OperationContext,
        delegate: RagService,
        listener: RagEventListener,
    ): RagService
}
