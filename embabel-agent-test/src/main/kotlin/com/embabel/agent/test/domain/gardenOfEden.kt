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
package com.embabel.agent.test.domain

data class GeneratedName(val name: String, val reason: String)

data class GeneratedNames(val names: List<GeneratedName>)

data class AllNames(val accepted: List<GeneratedName>, val rejected: List<GeneratedName>)

data class Garden(val name: String)

data class SpiPerson(val name: String)

data class WierdPerson(val name: String, val age: Int, val weirdness: String)

data class Return(
    val result: Result<*>,
    val capturedPrompt: String,
)
