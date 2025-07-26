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
package com.embabel.agent.a2a.spec

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.util.*

/**
 * Agent2Agent (A2A) Protocol Data Structures
 * Version: 0.2.1
 *
 * Kotlin implementation of all interfaces and data classes defined in the
 * A2A Protocol Specification for agent-to-agent communication.
 */

// ============================================================================
// 5. Agent Discovery: The Agent Card
// ============================================================================

/**
 * An AgentCard conveys key information:
 * - Overall details (version, name, description, uses)
 * - Skills: A set of capabilities the agent can perform
 * - Default modalities/content types supported by the agent.
 * - Authentication requirements
 */
data class AgentCard(
    /** Human-readable name of the agent. */
    val name: String,

    /** A human-readable description of the agent. Used to assist users and
     * other agents in understanding what the agent can do. */
    val description: String,

    /** A URL to the address the agent is hosted at. */
    val url: String,

    /** The service provider of the agent */
    val provider: AgentProvider? = null,

    /** The version of the agent - format is up to the provider. */
    val version: String,

    /** A URL to documentation for the agent. */
    val documentationUrl: String? = null,

    /** Optional capabilities supported by the agent. */
    val capabilities: AgentCapabilities,

    /** Security scheme details used for authenticating with this agent. */
    val securitySchemes: Map<String, SecurityScheme>? = null,

    /** Security requirements for contacting the agent. */
    val security: List<Map<String, List<String>>>? = null,

    /** The set of interaction modes that the agent supports across all skills.
     * This can be overridden per-skill. Supported mime types for input. */
    val defaultInputModes: List<String>,

    /** Supported mime types for output. */
    val defaultOutputModes: List<String>,

    /** Skills are a unit of capability that an agent can perform. */
    val skills: List<AgentSkill>,

    /** true if the agent supports providing an extended agent card when the user is authenticated.
     * Defaults to false if not specified. */
    val supportsAuthenticatedExtendedCard: Boolean? = null
)

/**
 * Represents the service provider of an agent.
 */
data class AgentProvider(
    /** Agent provider's organization name. */
    val organization: String,

    /** Agent provider's URL. */
    val url: String
)

/**
 * Defines optional capabilities supported by an agent.
 */
data class AgentCapabilities(
    /** true if the agent supports SSE. */
    val streaming: Boolean? = null,

    /** true if the agent can notify updates to client. */
    val pushNotifications: Boolean? = null,

    /** true if the agent exposes status change history for tasks. */
    val stateTransitionHistory: Boolean? = null
)

/**
 * Mirrors the OpenAPI Security Scheme Object
 * (https://swagger.io/specification/#security-scheme-object)
 */
sealed class SecurityScheme

data class APIKeySecurityScheme(
    val type: String = "apiKey",
    val name: String,
    val `in`: String // "query", "header", or "cookie"
) : SecurityScheme()

data class HTTPAuthSecurityScheme(
    val type: String = "http",
    val scheme: String, // "basic", "bearer", etc.
    val bearerFormat: String? = null
) : SecurityScheme()

data class OAuth2SecurityScheme(
    val type: String = "oauth2",
    val flows: OAuth2Flows
) : SecurityScheme()

data class OpenIdConnectSecurityScheme(
    val type: String = "openIdConnect",
    val openIdConnectUrl: String
) : SecurityScheme()

data class OAuth2Flows(
    val implicit: OAuth2Flow? = null,
    val password: OAuth2Flow? = null,
    val clientCredentials: OAuth2Flow? = null,
    val authorizationCode: OAuth2Flow? = null
)

data class OAuth2Flow(
    val authorizationUrl: String? = null,
    val tokenUrl: String? = null,
    val refreshUrl: String? = null,
    val scopes: Map<String, String>
)

/**
 * Represents a unit of capability that an agent can perform.
 */
data class AgentSkill(
    /** Unique identifier for the agent's skill. */
    val id: String,

    /** Human-readable name of the skill. */
    val name: String,

    /** Description of the skill - will be used by the client or a human
     * as a hint to understand what the skill does. */
    val description: String,

    /** Set of tag words describing classes of capabilities for this specific skill. */
    val tags: List<String>,

    /** The set of example scenarios that the skill can perform.
     * Will be used by the client as a hint to understand how the skill can be used. */
    val examples: List<String>? = null,

    /** The set of interaction modes that the skill supports
     * (if different to the default). Supported mime types for input. */
    val inputModes: List<String>? = null,

    /** Supported mime types for output. */
    val outputModes: List<String>? = null
)

// ============================================================================
// 6. Protocol Data Objects
// ============================================================================

/**
 * Represents the stateful unit of work being processed by the A2A Server for an A2A Client.
 */
data class Task(
    /** Unique identifier for the task */
    val id: String,

    /** Server-generated id for contextual alignment across interactions */
    val contextId: String,

    /** Current status of the task */
    val status: TaskStatus,

    val history: List<Message>? = null,

    /** Collection of artifacts created by the agent. */
    val artifacts: List<Artifact>? = null,

    /** Extension metadata. */
    val metadata: Map<String, Any>? = null,

    /** Event type */
    val kind: String = "task"
)

/**
 * Represents the current state and associated context of a Task.
 */
data class TaskStatus(
    val state: TaskState,

    /** Additional status updates for client */
    val message: Message? = null,

    /** ISO 8601 datetime string when the status was recorded. */
    val timestamp: String? = null
)

/**
 * Represents the possible states of a Task.
 */
enum class TaskState {
    submitted,
    working,
    input_required,
    completed,
    canceled,
    failed,
    rejected,
    auth_required,
    unknown;

    override fun toString(): String = when (this) {
        submitted -> "submitted"
        working -> "working"
        input_required -> "input-required"
        completed -> "completed"
        canceled -> "canceled"
        failed -> "failed"
        rejected -> "rejected"
        auth_required -> "auth-required"
        unknown -> "unknown"
    }
}

/**
 * Represents a single communication turn or a piece of contextual information
 * between a client and an agent.
 */
data class Message(
    /** Message sender's role */
    val role: String, // "user" or "agent"

    /** Message content */
    val parts: List<Part>,

    /** Extension metadata. */
    val metadata: Map<String, Any>? = null,

    /** List of tasks referenced as context by this message. */
    val referenceTaskIds: List<String>? = null,

    /** Identifier created by the message creator */
    val messageId: String,

    /** Identifier of task the message is related to */
    val taskId: String? = null,

    /** The context the message is associated with */
    val contextId: String? = null,

    /** Event type */
    val kind: String = "message"
)

/**
 * Base interface for all Part types
 */
interface PartBase {
    val metadata: Map<String, Any>?
}

/**
 * Represents a distinct piece of content within a Message or Artifact.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "kind"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = TextPart::class, name = "text"),
    JsonSubTypes.Type(value = FilePart::class, name = "file"),
    JsonSubTypes.Type(value = DataPart::class, name = "data")
)
sealed class Part : PartBase

/**
 * Represents a text segment within parts.
 */
data class TextPart(
    /** Text content */
    val text: String,

    /** Part type - text for TextParts */
    val kind: String = "text",

    override val metadata: Map<String, Any>? = null
) : Part()

/**
 * Represents a File segment within parts.
 */
data class FilePart(
    /** File content either as url or bytes */
    val file: FileData,

    /** Part type - file for FileParts */
    val kind: String = "file",

    override val metadata: Map<String, Any>? = null
) : Part()

/**
 * Represents a structured data segment within a message part.
 */
data class DataPart(
    /** Structured data content */
    val data: Map<String, Any>,

    /** Part type - data for DataParts */
    val kind: String = "data",

    override val metadata: Map<String, Any>? = null
) : Part()

/**
 * Base interface for file data
 */
interface FileBase {
    val name: String?
    val mimeType: String?
}

/**
 * Union type for file data - either bytes or URI
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "kind"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = FileWithBytes::class, name = "bytes"),
    JsonSubTypes.Type(value = FileWithUri::class, name = "uri")
)
sealed class FileData : FileBase

/**
 * Define the variant where 'bytes' is present and 'uri' is absent
 */
data class FileWithBytes(
    /** base64 encoded content of the file */
    val bytes: String,

    /** Original filename */
    override val name: String? = null,

    /** MIME type of the file */
    override val mimeType: String? = null,

    val kind: String = "bytes"
) : FileData()

/**
 * Define the variant where 'uri' is present and 'bytes' is absent
 */
data class FileWithUri(
    /** URL for the File content */
    val uri: String,

    /** Original filename */
    override val name: String? = null,

    /** MIME type of the file */
    override val mimeType: String? = null,

    val kind: String = "uri"
) : FileData()

/**
 * Represents a tangible output generated by the agent during a task.
 */
data class Artifact(
    /** Unique identifier for the artifact. */
    val artifactId: String = UUID.randomUUID().toString(),

    /** Optional name for the artifact. */
    val name: String? = null,

    /** Optional description for the artifact. */
    val description: String? = null,

    /** Artifact parts. */
    val parts: List<Part>,

    /** Extension metadata. */
    val metadata: Map<String, Any>? = null,
)

/**
 * Configuration provided by the client to the server for sending asynchronous
 * push notifications about task updates.
 */
data class PushNotificationConfig(
    /** URL for sending the push notifications. */
    val url: String,

    /** Token unique to this task/session. */
    val token: String? = null,

    val authentication: PushNotificationAuthenticationInfo? = null
)

/**
 * A generic structure for specifying authentication requirements, typically used within
 * PushNotificationConfig to describe how the A2A Server should authenticate to the client's webhook.
 */
data class PushNotificationAuthenticationInfo(
    /** Supported authentication schemes - e.g. Basic, Bearer */
    val schemes: List<String>,

    /** Optional credentials */
    val credentials: String? = null
)

/**
 * Used as the params object for the tasks/pushNotificationConfig/set method and as the
 * result object for the tasks/pushNotificationConfig/get method.
 */
data class TaskPushNotificationConfig(
    /** Task id. */
    val taskId: String,

    /** Push notification configuration. */
    val pushNotificationConfig: PushNotificationConfig
)

// ============================================================================
// 7. Protocol RPC Methods Request/Response Objects
// ============================================================================

/**
 * Sent by the client to the agent as a request. May create, continue or restart a task.
 */
data class MessageSendParams(
    /** The message being sent to the server. */
    val message: Message,

    /** Send message configuration. */
    val configuration: MessageSendConfiguration? = null,

    /** Extension metadata. */
    val metadata: Map<String, Any>? = null
)

/**
 * Configuration for the send message request.
 */
data class MessageSendConfiguration(
    /** Accepted output modalities by the client. */
    val acceptedOutputModes: List<String>,

    /** Number of recent messages to be retrieved. */
    val historyLength: Int? = null,

    /** Where the server should send notifications when disconnected. */
    val pushNotificationConfig: PushNotificationConfig? = null,

    /** If the server should treat the client as a blocking request. */
    val blocking: Boolean? = null
)

/**
 * Parameters for querying a task, including optional history length.
 */
data class TaskQueryParams(
    /** The ID of the task whose current state is to be retrieved. */
    val id: String,

    /** Number of recent messages to be retrieved. */
    val historyLength: Int? = null,

    val metadata: Map<String, Any>? = null
)

/**
 * Parameters containing only a task ID, used for simple task operations.
 */
data class TaskIdParams(
    /** Task id. */
    val id: String,

    val metadata: Map<String, Any>? = null
)

// ============================================================================
// 7.2. Streaming Response Objects
// ============================================================================


data class SendMessageSuccessResponse(
    override val jsonrpc: String = "2.0",
    override val id: String,
    /** Message or Task */
    val result: Any,
) : JSONRPCResponse()

/**
 * JSON-RPC response model for the 'message/stream' method.
 */
sealed class SendStreamingMessageResponse

data class SendStreamingMessageSuccessResponse(
    val jsonrpc: String = "2.0",
    val id: String,
    val result: StreamingResult
) : SendStreamingMessageResponse()

/**
 * Union type for streaming results
 */
sealed class StreamingResult

data class MessageResult(
    val message: Message
) : StreamingResult()

data class TaskResult(
    val task: Task
) : StreamingResult()

/**
 * Sent by server during sendStream or subscribe requests
 */
data class TaskStatusUpdateEvent(
    /** Task id */
    val taskId: String,

    /** The context the task is associated with */
    val contextId: String,

    /** Event type */
    val kind: String = "status-update",

    /** Current status of the task */
    val status: TaskStatus,

    /** Indicates the end of the event stream */
    val final: Boolean = false,

    /** Extension metadata. */
    val metadata: Map<String, Any>? = null
) : StreamingResult()

/**
 * Sent by server during sendStream or subscribe requests
 */
data class TaskArtifactUpdateEvent(
    /** Task id */
    val taskId: String,

    /** The context the task is associated with */
    val contextId: String,

    /** Event type */
    val kind: String = "artifact-update",

    /** Generated artifact */
    val artifact: Artifact,

    /** Indicates if this artifact appends to a previous one */
    val append: Boolean = false,

    /** Indicates if this is the last chunk of the artifact */
    val lastChunk: Boolean = false,

    /** Extension metadata. */
    val metadata: Map<String, Any>? = null
) : StreamingResult()

// ============================================================================
// 6.11. JSON-RPC Structures
// ============================================================================

/**
 * All A2A method calls are encapsulated in a JSON-RPC Request object.
 */
data class JSONRPCRequest(
    /** A String specifying the version of the JSON-RPC protocol. MUST be exactly "2.0". */
    val jsonrpc: String = "2.0",

    /** A String containing the name of the method to be invoked. */
    val method: String,

    /** A Structured value that holds the parameter values to be used during invocation. */
    val params: Any? = null,

    /** An identifier established by the Client. */
    val id: Any? = null,
) {

    fun successResponseWith(result: Any): JSONRPCResponse =
        JSONRPCSuccessResponse(jsonrpc = jsonrpc, id = id, result = result)

    fun errorResponseWith(error: JSONRPCError): JSONRPCErrorResponse =
        JSONRPCErrorResponse(jsonrpc = jsonrpc, id = id, error = error)
}


/**
 * Responses from the A2A Server are encapsulated in a JSON-RPC Response object.
 */
sealed class JSONRPCResponse {
    abstract val jsonrpc: String
    abstract val id: Any?
}

class JSONRPCSuccessResponse internal constructor(
    override val jsonrpc: String = "2.0",
    override val id: Any?,
    val result: Any
) : JSONRPCResponse() {

    override fun toString(): String = "JSONRPCSuccessResponse(jsonrpc=$jsonrpc, id=$id, result=$result)"
}

class JSONRPCErrorResponse internal constructor(
    override val jsonrpc: String = "2.0",
    override val id: Any?,
    val error: JSONRPCError
) : JSONRPCResponse() {

    override fun toString(): String = "JSONRPCErrorResponse(jsonrpc=$jsonrpc, id=$id, error=$error)"
}

/**
 * Represents a JSON-RPC 2.0 Error object.
 * This is typically included in a JSONRPCErrorResponse when an error occurs.
 */
data class JSONRPCError(
    /** A Number that indicates the error type that occurred. */
    val code: Int,

    /** A String providing a short description of the error. */
    val message: String,

    /** A Primitive or Structured value that contains additional information about the error. */
    val data: Any? = null
)

// ============================================================================
// Error Code Constants
// ============================================================================

object A2AErrorCodes {
    // Standard JSON-RPC Errors
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    // A2A-Specific Errors (-32000 to -32099)
    const val TASK_NOT_FOUND = -32001
    const val TASK_NOT_CANCELABLE = -32002
    const val PUSH_NOTIFICATION_NOT_SUPPORTED = -32003
    const val UNSUPPORTED_OPERATION = -32004
    const val CONTENT_TYPE_NOT_SUPPORTED = -32005
    const val INVALID_AGENT_RESPONSE = -32006
}

/**
 * Not really documented in the spec, but used in the A2A Server
 */
data class ResponseWrapper(
    val root: JSONRPCResponse
)

/**
 * Parameters for streaming messages
 */
data class MessageStreamParams(
    /** The ID of the task to stream messages for */
    val taskId: String,

    /** The message to process */
    val message: Message? = null,

    /** Optional configuration for streaming */
    val configuration: StreamConfiguration? = null,

    /** Extension metadata */
    val metadata: Map<String, Any>? = null
)

/**
 * Configuration for message streaming
 */
data class StreamConfiguration(
    /** Whether to include message history */
    val includeHistory: Boolean = false,

    /** Maximum number of messages to include in history */
    val historyLength: Int? = null,

    /** Whether to stream in real-time */
    val realTime: Boolean = true
)
