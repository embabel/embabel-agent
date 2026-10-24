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
package com.embabel.agent.a2a.server

import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AResponse
import org.a2aproject.sdk.jsonrpc.common.wrappers.NonStreamingJSONRPCRequest
import org.a2aproject.sdk.jsonrpc.common.wrappers.StreamingJSONRPCRequest
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
    fun handleJsonRpc(request: NonStreamingJSONRPCRequest<*>): A2AResponse<*>

    /**
     * Handles a streaming JSON-RPC request using Server-Sent Events (SSE).
     * This method is called when a client requests a streaming response for methods like "SendStreamingMessage".
     *
     * The default implementation throws [UnsupportedOperationException] as streaming is not supported.
     * Override this method in implementations that support streaming responses.
     *
     * @param request The JSON-RPC request containing the method name, parameters, and request ID
     * @param protocolVersion The A2A version the client speaks: stream events are written in it
     * @return An [SseEmitter] that will be used to send streaming events to the client
     * @throws UnsupportedOperationException if streaming is not supported by this implementation
     * @see SseEmitter
     * @see StreamingJSONRPCRequest
     */
    fun handleJsonRpcStream(
        request: StreamingJSONRPCRequest<*>,
        protocolVersion: A2AProtocolVersion = A2AProtocolVersion.V1_0,
    ): SseEmitter {
        throw UnsupportedOperationException("Streaming not supported by this implementation")
    }
}
