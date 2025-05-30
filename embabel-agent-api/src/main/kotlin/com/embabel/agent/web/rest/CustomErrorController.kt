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
package com.embabel.agent.web.rest

import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.boot.web.servlet.error.ErrorController
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class CustomErrorController : ErrorController {

    private val logger = LoggerFactory.getLogger(CustomErrorController::class.java)

    @RequestMapping("/error")
    fun handleError(request: HttpServletRequest): ResponseEntity<Map<String, Any>> {
        val status = getErrorStatus(request)
        val path = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) as String?
        val method = request.method

        if (status == HttpStatus.NOT_FOUND) {
            logger.warn("404 Not Found - Method: $method, Path: $path, Client IP: ${getClientIp(request)}")
        }

        return ResponseEntity.status(status).body(
            mapOf<String, Any>(
                "status" to status.value(),
                "error" to status.reasonPhrase,
                "path" to (path ?: "Unknown path"),
            )
        )
    }

    private fun getErrorStatus(request: HttpServletRequest): HttpStatus {
        val statusCode = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) as Int?
        return statusCode?.let { HttpStatus.valueOf(it) } ?: HttpStatus.INTERNAL_SERVER_ERROR
    }

    private fun getClientIp(request: HttpServletRequest): String {
        return request.getHeader("X-Forwarded-For")
            ?: request.getHeader("X-Real-IP")
            ?: request.remoteAddr
    }
}
