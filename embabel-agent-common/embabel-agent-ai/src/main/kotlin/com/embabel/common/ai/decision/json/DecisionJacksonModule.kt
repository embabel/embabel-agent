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
package com.embabel.common.ai.decision.json

import org.jetbrains.annotations.ApiStatus
import tools.jackson.core.Version
import tools.jackson.databind.JacksonModule
import tools.jackson.databind.module.SimpleDeserializers
import tools.jackson.databind.module.SimpleSerializers

/**
 * The Jackson module that reads and writes decision specs, questions, requests, options,
 * capabilities, responses, answers and rating results.
 *
 * Field names are fixed. The mapper's naming strategy does not change them, so every application
 * reads and writes the same JSON. Reading is strict: a repeated member, an unknown member, an
 * unknown question kind, execution mode, outcome status or failure reason, a value of the wrong
 * JSON type and any value the public builders and factories refuse all fail with a
 * `MismatchedInputException`. These checks run on the token stream, so they hold whatever the
 * mapper's own duplicate and unknown-property settings are.
 *
 * The module registers bindings only for the decision types. Every other type keeps the JSON it
 * had before, including the proposition and classification results and model provenance that
 * appear inside a response.
 *
 * Register it on a plain mapper:
 *
 * ```java
 * JsonMapper mapper = JsonMapper.builder()
 *     .addModule(new DecisionJacksonModule())
 *     .build();
 * String json = mapper.writeValueAsString(spec);
 * DecisionSpec read = mapper.readValue(json, DecisionSpec.class);
 * ```
 *
 * The module is also listed for Java's `ServiceLoader`, so `JsonMapper.builder().findAndAddModules()`
 * picks it up. Spring Boot's Jackson auto-configuration finds modules this way, so a Spring Boot
 * application needs no bean or other setup to use it.
 */
@ApiStatus.Experimental
class DecisionJacksonModule : JacksonModule() {

    /**
     * Returns the name Jackson uses for this module in diagnostics.
     *
     * @return `DecisionJacksonModule`
     */
    override fun getModuleName(): String = "DecisionJacksonModule"

    /**
     * Returns the module version. The module ships inside Embabel and has no version of its own.
     *
     * @return the unknown version
     */
    override fun version(): Version = Version.unknownVersion()

    /**
     * Adds the serializers and deserializers for the decision types to the mapper being built.
     *
     * @param context the mapper's setup context
     */
    override fun setupModule(context: SetupContext) {
        val serializers = SimpleSerializers()
        val deserializers = SimpleDeserializers()
        SpecJson.register(serializers, deserializers)
        ResponseJson.register(serializers, deserializers)
        context.addSerializers(serializers)
        context.addDeserializers(deserializers)
    }
}
