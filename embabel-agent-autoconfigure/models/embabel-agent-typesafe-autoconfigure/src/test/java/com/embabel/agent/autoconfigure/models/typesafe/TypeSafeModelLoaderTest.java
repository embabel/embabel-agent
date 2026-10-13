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
package com.embabel.agent.autoconfigure.models.typesafe;

import com.embabel.agent.config.models.typesafe.TypeSafeModelLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TypeSafeModelLoaderTest {

    @Test
    void theStarterCatalogDefinesJevLatest() {
        var models = new TypeSafeModelLoader().loadAutoConfigMetadata().getModels();
        assertThat(models).extracting("name").containsExactly("jev-latest");
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "malformed", "empty", "missing"})
    void anUnusableCatalogFailsNamingTheResource(String catalog) {
        var path = "classpath:catalogs/" + catalog + ".yml";
        var loader = new TypeSafeModelLoader(new DefaultResourceLoader(), path);
        assertThatThrownBy(loader::loadAutoConfigMetadata)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(path);
    }
}
