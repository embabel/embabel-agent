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

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder
import org.springframework.core.ParameterizedTypeReference
import java.lang.reflect.Field
import java.lang.reflect.Type
import java.util.function.Predicate

/**
 * Extension of [JacksonOutputConverter] that allows for filtering of properties of the generated object via a predicate.
 */
open class FilteringJacksonOutputConverter<T> private constructor(
    type: Type,
    objectMapper: ObjectMapper,
    private val fieldFilter: Predicate<Field>,
) : JacksonOutputConverter<T>(type, objectMapper) {

    constructor(
        clazz: Class<T>,
        objectMapper: ObjectMapper,
        fieldFilter: Predicate<Field>,
    ) : this(clazz as Type, objectMapper, fieldFilter)

    constructor(
        typeReference: ParameterizedTypeReference<T>,
        objectMapper: ObjectMapper,
        fieldFilter: Predicate<Field>,
    ) : this(typeReference.type, objectMapper, fieldFilter)

    override fun schemaGeneratorConfigBuilder(): SchemaGeneratorConfigBuilder {
        val configBuilder = super.schemaGeneratorConfigBuilder()
        configBuilder.forFields().withIgnoreCheck { !fieldFilter.test(it.member.rawMember) }
        return configBuilder
    }

}
