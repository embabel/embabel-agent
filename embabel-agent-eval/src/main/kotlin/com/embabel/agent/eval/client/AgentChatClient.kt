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
package com.embabel.agent.eval.client

import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

data class KnowledgeContext(
    val name: String,
    val description: String,
    val schemaName: String = "personal",
    val id: String = name,
)

data class SessionCreationRequest(
    val user: String,
    val chatbot: String,
)

data class SessionCreationResponse(
    val sessionId: String,
)


/**
 * Simple client to Agent chat
 */
@Service
class AgentChatClient(
    private val restClient: RestClient = RestClient.create(),
    private val agentHost: String = "http://localhost:8081",
    private val agentChatPath: String = "/api/v1/chat",
    private val boogieHost: String = "http://localhost:8080",
    private val boogieContextPath: String = "/api/v1/graphs",
    private val apiKey: String = "treehorn",
) {

    companion object {
        private const val MESSAGE_NO_RESPONSE_BODY = "No response body"
    }

    // TODO share with the BoogieClient
    val defaultHeaders = HttpHeaders().apply {
        set("Content-Type", "application/json")
        set("X-API-KEY", apiKey)
    }

    fun createKnowledgeContext(knowledgeContext: KnowledgeContext): String {
        return restClient
            .put()
            .uri("${boogieHost}/${boogieContextPath}")
            .headers { it.putAll(defaultHeaders) }
            .body(knowledgeContext)
            .retrieve()
            .body<String>()
            ?: throw IllegalStateException(MESSAGE_NO_RESPONSE_BODY)
    }

    fun createSession(sessionCreationRequest: SessionCreationRequest): SessionCreationResponse {
        return restClient
            .put()
            .uri("${agentHost}/${agentChatPath}/sessions")
            .headers { it.putAll(defaultHeaders) }
            .body(sessionCreationRequest)
            .retrieve()
            .body<SessionCreationResponse>()
            ?: throw IllegalStateException(MESSAGE_NO_RESPONSE_BODY)
    }

//    fun ingestDocument(knowledgeContext: KnowledgeContext): String {
//        val entity = HttpEntity(knowledgeContext, defaultHeaders)
//        return restTemplate.exchange(
//            "${boogieHost}/${boogieContextPath}",
//            HttpMethod.PUT,
//            entity,
//            String::class.java,
//        ).body ?: throw IllegalStateException(noResponseMessage)
//    }

    fun getObjectContext(id: String): ObjectContext {
        return restClient
            .get()
            .uri("${agentHost}/${agentChatPath}/objectContexts/{id}", id)
            .retrieve()
            .body<ObjectContext>()
            ?: throw IllegalStateException(MESSAGE_NO_RESPONSE_BODY)
    }

    fun respond(chatRequest: ChatRequest): MessageResponse {
        return restClient
            .put()
            .uri("${agentHost}/${agentChatPath}/messages")
            .body(chatRequest)
            .retrieve()
            .body<MessageResponse>()
            ?: throw IllegalStateException(MESSAGE_NO_RESPONSE_BODY)
    }

}
