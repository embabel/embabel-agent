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
package com.embabel.agent.spi.support.persistence

import com.embabel.agent.core.hitl.FormBindingRequest
import com.embabel.agent.core.hitl.ValidationError
import com.embabel.agent.core.persistence.AgentProcessPersistenceException
import com.embabel.agent.core.persistence.BlackboardEntryDeserializationContext
import com.embabel.agent.core.persistence.BlackboardEntrySerializationContext
import com.embabel.agent.core.persistence.BlackboardEntrySerializer
import com.embabel.agent.core.persistence.SerializedBlackboardValue
import com.embabel.ux.form.Form
import org.springframework.http.MediaType
import org.springframework.util.ClassUtils
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant

/**
 * Preserves the identity and binding data of a pending [FormBindingRequest].
 * Register this serializer as a Spring bean to enable it for durable processes.
 */
class FormBindingRequestSerializer(
    private val objectMapper: ObjectMapper,
) : BlackboardEntrySerializer {

    override fun supportsSerialization(value: Any): Boolean = value is FormBindingRequest<*>

    override fun supportsDeserialization(value: SerializedBlackboardValue): Boolean =
        value.typeName == TYPE_NAME || value.typeName == FormBindingRequest::class.java.name

    override fun serialize(
        value: Any,
        context: BlackboardEntrySerializationContext,
    ): SerializedBlackboardValue {
        val request = value as FormBindingRequest<*>
        val snapshot = Snapshot(
            id = request.id,
            timestamp = request.timestamp,
            payload = request.payload,
            outputClass = request.outputClass.name,
            population = request.population?.let { objectMapper.valueToTree(it) },
            validationErrors = request.validationErrors.map {
                ErrorSnapshot(it.javaClass.name, objectMapper.valueToTree(it))
            },
            persistent = request.persistent(),
            bindingName = request.bindingName,
        )
        return SerializedBlackboardValue(
            typeName = TYPE_NAME,
            contentType = MediaType.APPLICATION_JSON_VALUE,
            payload = objectMapper.writeValueAsBytes(snapshot),
        )
    }

    override fun deserialize(
        value: SerializedBlackboardValue,
        context: BlackboardEntryDeserializationContext,
    ): Any = try {
        require(MediaType.parseMediaType(value.contentType).isCompatibleWith(MediaType.APPLICATION_JSON)) {
            "Unsupported form binding request content type [${value.contentType}]"
        }
        val snapshot = objectMapper.readValue(value.payload, Snapshot::class.java)
        restore(snapshot, loadClass(snapshot.outputClass))
    } catch (e: Exception) {
        throw AgentProcessPersistenceException("Cannot deserialize form binding request", e)
    }

    private fun <O : Any> restore(snapshot: Snapshot, outputClass: Class<O>): FormBindingRequest<O> =
        FormBindingRequest(
            form = snapshot.payload,
            outputClass = outputClass,
            population = snapshot.population?.let { objectMapper.treeToValue(it, outputClass) },
            validationErrors = snapshot.validationErrors.map { error ->
                objectMapper.treeToValue(error.payload, loadClass(error.typeName).asSubclass(ValidationError::class.java))
            },
            persistent = snapshot.persistent,
            bindingName = snapshot.bindingName,
            id = snapshot.id,
            timestamp = snapshot.timestamp,
        )

    private fun loadClass(name: String): Class<*> =
        ClassUtils.forName(name, ClassUtils.getDefaultClassLoader())

    private data class Snapshot(
        val id: String,
        val timestamp: Instant,
        val payload: Form,
        val outputClass: String,
        val population: JsonNode? = null,
        val validationErrors: List<ErrorSnapshot> = emptyList(),
        val persistent: Boolean = false,
        val bindingName: String? = null,
    )

    private data class ErrorSnapshot(val typeName: String, val payload: JsonNode)

    private companion object {
        const val TYPE_NAME = "embabel.form-binding-request.v1"
    }
}
