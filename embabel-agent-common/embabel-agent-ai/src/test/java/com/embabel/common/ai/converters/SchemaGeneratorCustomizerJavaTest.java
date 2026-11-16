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

import com.github.victools.jsonschema.generator.CustomDefinition;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaGeneratorCustomizerJavaTest {

    record Tags(String csv) {
    }

    record TaggedDog(String name, Tags tags) {
    }

    @Test
    void lambdaCustomizerChangesTheGeneratedSchema() {
        SchemaGeneratorCustomizer tagsAsArray = builder -> builder.forTypesInGeneral()
                .withCustomDefinitionProvider((javaType, context) -> javaType.getErasedType() == Tags.class
                        ? new CustomDefinition(context.getGeneratorConfig().createObjectNode().put("type", "array"))
                        : null);
        var builder = new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON);

        tagsAsArray.customize(builder);

        var schema = new SchemaGenerator(builder.build()).generateSchema(TaggedDog.class);
        assertThat(schema.at("/properties/tags/type").asText()).isEqualTo("array");
    }
}
