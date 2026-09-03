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
package com.embabel.agent.core.persistence

/**
 * Raised when an agent process cannot be checkpointed or restored.
 *
 * This exception represents framework persistence failures such as unsupported
 * blackboard values, invalid serialized payloads, stale snapshot versions, or
 * inability to reconstruct a process from a stored checkpoint.
 */
class AgentProcessPersistenceException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Extension point for application-specific blackboard persistence.
 *
 * Implementations can store values as DTOs, encrypted payloads, entity references,
 * or any other representation that can be safely restored later.
 *
 * Implementations are expected to be stateless and safe for concurrent use. They
 * should serialize value state, not live runtime resources such as Spring beans,
 * open streams, sockets, threads, or ORM sessions.
 */
interface BlackboardEntrySerializer {

    /**
     * Return true when this serializer should handle the given live blackboard value.
     *
     * Custom serializers are typically used for values that need special treatment,
     * such as Hibernate entities stored as references or sensitive values stored in
     * encrypted form.
     */
    fun supportsSerialization(value: Any): Boolean

    /**
     * Return true when this serializer should restore the serialized blackboard value.
     *
     * Implementations usually inspect [SerializedBlackboardValue.typeName],
     * [SerializedBlackboardValue.contentType], or metadata written during serialization.
     *
     * **The default returns `false`.** A serializer that overrides only
     * [supportsSerialization] and [serialize] will write values but never restore
     * them — the fallback serializer handles deserialization instead, which will
     * likely produce wrong results or fail. Override both [supportsDeserialization]
     * and [deserialize] for a complete serialization round-trip.
     */
    fun supportsDeserialization(value: SerializedBlackboardValue): Boolean = false

    /**
     * Convert a live blackboard value into a durable payload.
     *
     * The returned [SerializedBlackboardValue] must contain enough type and format
     * information for this serializer, or another compatible serializer, to restore
     * the value later.
     */
    fun serialize(
        value: Any,
        context: BlackboardEntrySerializationContext = BlackboardEntrySerializationContext(),
    ): SerializedBlackboardValue

    /**
     * Convert a durable payload back into a blackboard value.
     */
    fun deserialize(
        value: SerializedBlackboardValue,
        context: BlackboardEntryDeserializationContext = BlackboardEntryDeserializationContext(),
    ): Any
}

/**
 * Context available while serializing a single blackboard entry.
 *
 * All fields are optional so serializers can be reused in tests and outside an
 * active agent process. [key] is present for named bindings; [entrySequence]
 * identifies the entry's order in the blackboard when available.
 */
data class BlackboardEntrySerializationContext(
    val processId: String? = null,
    val key: String? = null,
    val entrySequence: Long? = null,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Context available while restoring a single blackboard entry.
 */
data class BlackboardEntryDeserializationContext(
    val processId: String? = null,
    val key: String? = null,
    val entrySequence: Long? = null,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Serialized representation of a blackboard value.
 *
 * @param typeName logical or JVM type name used by the serializer to restore this value
 * @param contentType media type of [payload], for example `application/json`
 * @param payload serialized bytes for the value
 * @param metadata optional serializer-specific metadata, such as schema version or entity class
 */
data class SerializedBlackboardValue(
    val typeName: String,
    val contentType: String,
    val payload: ByteArray,
    val metadata: Map<String, String> = emptyMap(),
) {

    override fun equals(other: Any?): Boolean =
        this === other ||
                other is SerializedBlackboardValue &&
                typeName == other.typeName &&
                contentType == other.contentType &&
                payload.contentEquals(other.payload) &&
                metadata == other.metadata

    override fun hashCode(): Int {
        var result = typeName.hashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + metadata.hashCode()
        return result
    }
}
