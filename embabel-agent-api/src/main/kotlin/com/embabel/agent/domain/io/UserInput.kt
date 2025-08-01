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
package com.embabel.agent.domain.io

import com.embabel.agent.domain.library.HasContent
import com.embabel.common.core.types.Timestamped
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import java.time.Instant

interface SystemInput : Timestamped

/**
 * Special class that represents a single user input
 * Starting point for many flows.
 */
@JsonIgnoreProperties(value = ["timestamp"], allowGetters = true)
data class UserInput(
    @get:JsonPropertyDescription("user input")
    override val content: String,
    override val timestamp: Instant = Instant.now(),
) : SystemInput, HasContent {

    // For Java and Graal
    @JsonCreator
    constructor (@JsonProperty("content") content: String) : this(content, Instant.now())
}
