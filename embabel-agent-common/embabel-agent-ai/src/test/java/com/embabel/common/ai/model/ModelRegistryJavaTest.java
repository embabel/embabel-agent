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
package com.embabel.common.ai.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Builds and uses a {@link ModelRegistry} from Java, with a capability that exists only in this test.
 */
class ModelRegistryJavaTest {

    interface Drawing extends ModelCapability {
    }

    static final class Drawer implements Drawing {
    }

    /** Serves one drawing model, named {@code served}. */
    static final class DrawingSource implements ModelSource<Drawing> {
        final Drawer served = new Drawer();

        @Override
        public String getProvider() {
            return "test";
        }

        @Override
        public Class<Drawing> getType() {
            return Drawing.class;
        }

        @Override
        public List<String> modelNames() {
            return List.of("served");
        }

        @Override
        public Drawing modelNamed(String name) {
            return name.equals("served") ? served : null;
        }
    }

    @Test
    void builderFamilySpecSourceAndSelectorsWorkFromJava() {
        Drawer sketch = new Drawer();
        DrawingSource source = new DrawingSource();
        // The two-argument FamilySpec constructor uses the default advice text.
        ModelRegistry.FamilySpec drawingFamily = new ModelRegistry.FamilySpec("drawing", "TestBuilder");
        ModelRegistry<Drawing> registry = ModelRegistry.<Drawing>builder()
                .family(Drawing.class, drawingFamily)
                .register("sketch", sketch)
                .source(source)
                .role(Drawing.class, "served-role", "served")
                // describeWith takes a Java lambda. The description is used only in error messages.
                .describeWith(model -> "DRAWING")
                .build();

        ServiceSelector<Drawing> drawings = registry.select(Drawing.class);
        // The only registered model is the default.
        assertSame(sketch, drawings.defaultService());
        // A role can name a model the source serves.
        assertSame(source.served, drawings.byRole("served-role"));
        // Source models are not listed with the registered models.
        assertEquals(List.of("sketch"), List.copyOf(registry.registeredModels().keySet()));
    }
}
