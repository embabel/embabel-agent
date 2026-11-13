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
package com.embabel.agent.a2a.server.support

import org.a2aproject.sdk.grpc.utils.JSONRPCUtils
import org.a2aproject.sdk.grpc.utils.ProtoUtils
import org.a2aproject.sdk.spec.StreamingEventKind
import org.a2aproject.sdk.spec.Task
import org.a2aproject.sdk.spec.TaskState
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent
import org.springframework.core.MethodParameter
import org.springframework.http.converter.StringHttpMessageConverter
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.async.StandardServletAsyncWebRequest
import org.springframework.web.context.request.async.WebAsyncUtils
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitterReturnValueHandler
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Duration
import java.time.Instant

/**
 * Connects an [SseEmitter] to a mock HTTP response, as Spring MVC does when a controller returns it,
 * so tests can read what a client receives.
 */
class SseCapture(emitter: SseEmitter) {

    private val request = MockHttpServletRequest().apply { isAsyncSupported = true }
    private val response = MockHttpServletResponse()
    private val asyncManager = WebAsyncUtils.getAsyncManager(request)

    init {
        asyncManager.setAsyncWebRequest(StandardServletAsyncWebRequest(request, response))
        ResponseBodyEmitterReturnValueHandler(listOf(StringHttpMessageConverter())).handleReturnValue(
            emitter,
            MethodParameter(SseCapture::class.java.getDeclaredMethod("returnType"), -1),
            ModelAndViewContainer(),
            ServletWebRequest(request, response),
        )
    }

    val closed: Boolean
        get() = asyncManager.hasConcurrentResult()

    fun events(): List<StreamingEventKind> =
        response.contentAsString.lineSequence()
            .filter { it.startsWith("data:") }
            .map { ProtoUtils.FromProto.streamingEventKind(JSONRPCUtils.parseResponseEvent(it.removePrefix("data:"))) }
            .toList()

    fun states(): List<TaskState> = events().mapNotNull {
        when (it) {
            is Task -> it.status().state()
            is TaskStatusUpdateEvent -> it.status().state()
            else -> null
        }
    }

    /**
     * Ends the connection as a client going away would.
     */
    fun disconnect() {
        request.asyncContext!!.complete()
    }

    fun awaitClosed(timeout: Duration = Duration.ofSeconds(5)): SseCapture {
        val deadline = Instant.now().plus(timeout)
        while (!closed) {
            check(Instant.now().isBefore(deadline)) { "Stream was not closed, received: ${states()}" }
            Thread.sleep(10)
        }
        return this
    }

    @Suppress("unused")
    private fun returnType(): SseEmitter? = null
}
