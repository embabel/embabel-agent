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
package com.embabel.agent.a2a.server

import io.a2a.spec.JSONRPCResponse
import io.a2a.spec.NonStreamingJSONRPCRequest
import io.a2a.spec.StreamingJSONRPCRequest
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * Handles JSON-RPC requests according to the A2A protocol.
 */
interface A2ARequestHandler {

    /**
     * Handle a JSON-RPC request according to the A2A protocol.
     * @param request the JSON-RPC request
     * @return the JSON-RPC response
     */
    fun handleJsonRpc(request: NonStreamingJSONRPCRequest<*>): JSONRPCResponse<*>

    /**
     * Handles a streaming JSON-RPC request using Server-Sent Events (SSE).
     * This method is called when a client requests a streaming response for methods like "message/stream".
     *
     * The default implementation throws [UnsupportedOperationException] as streaming is not supported.
     * Override this method in implementations that support streaming responses.
     *
     * @param request The JSON-RPC request containing the method name, parameters, and request ID
     * @return An [SseEmitter] that will be used to send streaming events to the client
     * @throws UnsupportedOperationException if streaming is not supported by this implementation
     * @see SseEmitter
     * @see StreamingJSONRPCRequest
     */
    fun handleJsonRpcStream(request: StreamingJSONRPCRequest<*>): SseEmitter {
        throw UnsupportedOperationException("Streaming not supported by this implementation")
    }
}
