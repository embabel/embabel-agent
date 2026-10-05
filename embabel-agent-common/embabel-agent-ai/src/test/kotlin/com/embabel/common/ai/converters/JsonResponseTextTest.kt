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
package com.embabel.common.ai.converters

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JsonResponseTextTest {
    @Test
    fun `unwraps only one complete JSON fence`() {
        val json = "{\"value\":1}"
        listOf("```json\n$json\n```", "```\n$json\n```", " \r\n```json\r\n$json\r\n```\t", "```json\n$json```").forEach {
            assertEquals(json, JsonResponseText.withoutCodeFence(it))
        }
    }

    @Test
    fun `preserves text that strict parsing must reject`() {
        listOf("plain text", "{\"value\":1}", "Before\n```json\n{}\n```", "```json\n{}\n```\nAfter",
            "```json\n{}\n```\n```json\n{}\n```", "```json\n{\"value\":\"```\"}\n```",
            "```yaml\n{}\n```", "```json {} ```", "```json\n{}").forEach {
            assertEquals(it, JsonResponseText.withoutCodeFence(it))
        }
    }
}
