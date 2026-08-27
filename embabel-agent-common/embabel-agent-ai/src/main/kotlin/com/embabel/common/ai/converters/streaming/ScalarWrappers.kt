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
package com.embabel.common.ai.converters.streaming

/**
 * Wrapper for streaming a scalar string result from an LLM.
 *
 * When passing String::class to createObjectStreamWithThinking(), the generated schema
 * {"type":"string"} causes the LLM to return bare strings instead of JSON objects,
 * which the streaming parser cannot recognize as structured output.
 *
 * Use StringResult instead — it produces schema {"type":"object","properties":{"value":{"type":"string"}}}
 * so the LLM returns {"value":"..."} which is correctly parsed and emitted as an object event.
 */
data class StringResult(val value: String)
