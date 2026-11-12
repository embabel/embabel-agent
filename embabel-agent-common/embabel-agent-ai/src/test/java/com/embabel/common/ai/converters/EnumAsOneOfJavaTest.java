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
package com.embabel.common.ai.converters;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms that {@link EnumAsOneOf} and {@link EnumConstantDescriptionProvider}
 * are usable from Java with no special configuration.
 *
 * {@code @EnumAsOneOf} is a Kotlin annotation compiled to a standard Java
 * {@code @interface}, so it can be placed on any Java enum. The provider is a
 * plain Java-callable class — no Kotlin-specific APIs are needed to construct
 * or register it.
 */
class EnumAsOneOfJavaTest {

    // Java enum annotated with @EnumAsOneOf — same as the Kotlin usage example
    @EnumAsOneOf
    enum JavaPriority {
        @JsonPropertyDescription("Needs same-day response") URGENT,
        @JsonPropertyDescription("Standard turnaround")    NORMAL,
        OTHER  // deliberately un-annotated
    }

    record Holder(JavaPriority priority) {}

    @Test
    void enumAsOneOfAnnotationIsPlaceableOnJavaEnum() {
        // @EnumAsOneOf must be readable at runtime so the provider can detect it.
        assertThat(JavaPriority.class.isAnnotationPresent(EnumAsOneOf.class)).isTrue();
    }

    @Test
    void enumConstantDescriptionProviderEmitsOneOfFromJavaEnum() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        // EnumConstantDescriptionProvider is constructable from Java.
        EnumConstantDescriptionProvider provider = new EnumConstantDescriptionProvider(objectMapper);

        // Wire the provider into a converter the same way ChatClientLlmOperations does.
        // FilteringJacksonOutputConverter.schemaGeneratorConfigBuilder() is protected
        // and open, so we override it in a subclass here.
        FilteringJacksonOutputConverter<Holder> converter =
                new FilteringJacksonOutputConverter<Holder>(
                        Holder.class,
                        objectMapper,
                        field -> true,
                        RequiredFieldNormalization.ENABLED
                ) {
                    @Override
                    protected com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder schemaGeneratorConfigBuilder() {
                        return super.schemaGeneratorConfigBuilder().with(provider);
                    }
                };

        JsonNode schema = objectMapper.readTree(converter.getJsonSchema());
        JsonNode priorityNode = schema.path("properties").path("priority");

        // The provider should have emitted oneOf for the Java enum.
        assertThat(priorityNode.has("oneOf")).isTrue();
        assertThat(priorityNode.path("type").asText()).isEqualTo("string");

        JsonNode oneOf = priorityNode.path("oneOf");
        assertThat(oneOf.isArray()).isTrue();
        assertThat(oneOf.size()).isEqualTo(3);

        // Find the URGENT entry and confirm its description.
        JsonNode urgent = null;
        for (JsonNode entry : oneOf) {
            if (JavaPriority.URGENT.name().equals(entry.path("const").asText())) {
                urgent = entry;
                break;
            }
        }
        assertThat(urgent).isNotNull();
        assertThat(urgent.path("description").asText()).isEqualTo("Needs same-day response");

        // Un-annotated constant has const but no description.
        JsonNode other = null;
        for (JsonNode entry : oneOf) {
            if (JavaPriority.OTHER.name().equals(entry.path("const").asText())) {
                other = entry;
                break;
            }
        }
        assertThat(other).isNotNull();
        assertThat(other.has("description")).isFalse();
    }
}
