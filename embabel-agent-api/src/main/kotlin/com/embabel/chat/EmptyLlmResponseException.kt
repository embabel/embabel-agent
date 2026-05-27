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
package com.embabel.chat

/**
 * Thrown when an LLM call returns an empty or blank response where text
 * was expected. Distinct from a generic [IllegalArgumentException] so
 * callers can recognise the case and substitute a friendlier prompt
 * (e.g. "I don't have a response — could you rephrase?") rather than
 * leak a technical error message.
 *
 * Common with weaker open-weights chat models that occasionally go silent
 * after a tool call. Catch by type — do NOT pattern-match on
 * `e.message`, which is brittle.
 */
class EmptyLlmResponseException(
    message: String = "LLM returned an empty or blank response",
) : RuntimeException(message)
