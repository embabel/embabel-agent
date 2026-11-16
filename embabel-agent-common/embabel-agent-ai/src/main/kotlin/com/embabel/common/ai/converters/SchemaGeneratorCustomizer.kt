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

import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder

/**
 * Customizes the victools [SchemaGeneratorConfigBuilder] that generates the JSON schema of structured output,
 * for example to add victools modules or type mappings for types the default configuration describes wrongly.
 *
 * Declare implementations as Spring beans. Embabel applies them in bean order
 * ([org.springframework.core.annotation.Order]) to streaming and non-streaming calls alike,
 * after its own configuration, including [EnumConstantDescriptionProvider].
 * Victools uses the first custom definition provider that returns a definition for a type, so a custom definition
 * from an earlier customizer takes precedence over one from a later customizer, and cannot replace the `oneOf`
 * schema of an [@EnumAsOneOf][EnumAsOneOf] enum.
 */
fun interface SchemaGeneratorCustomizer {

    fun customize(builder: SchemaGeneratorConfigBuilder)
}
