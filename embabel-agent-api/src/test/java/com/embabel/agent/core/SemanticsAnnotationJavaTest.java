/*
 * Copyright 2024-2025 Embabel Pty Ltd.
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
package com.embabel.agent.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java tests for the @Semantics annotation.
 * Verifies that semantic metadata can be applied to Java classes and read via JvmType.
 */
class SemanticsAnnotationJavaTest {

    /**
     * Simple company entity for testing relationships.
     */
    static class Company {
        private String name;

        public Company(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    /**
     * Java class with @Semantics annotation on a field.
     */
    static class JavaEmployee {
        private String name;

        @Semantics({
            @With(key = "predicate", value = "works at"),
            @With(key = "inverse", value = "employs"),
            @With(key = "aliases", value = "is employed by, works for")
        })
        private Company worksAt;

        public JavaEmployee(String name, Company worksAt) {
            this.name = name;
            this.worksAt = worksAt;
        }

        public String getName() {
            return name;
        }

        public Company getWorksAt() {
            return worksAt;
        }
    }

    /**
     * Java class with multiple annotated fields.
     */
    static class JavaPerson {
        private String name;

        @Semantics({
            @With(key = "predicate", value = "lives in"),
            @With(key = "inverse", value = "is home to")
        })
        private String city;

        @Semantics({
            @With(key = "predicate", value = "knows"),
            @With(key = "inverse", value = "is known by")
        })
        private JavaPerson friend;

        public JavaPerson(String name, String city, JavaPerson friend) {
            this.name = name;
            this.city = city;
            this.friend = friend;
        }
    }

    /**
     * Java class with empty @Semantics annotation.
     */
    static class EmptyAnnotation {
        @Semantics
        private String field;
    }

    @Test
    void extractsMetadataFromJavaClassField() {
        // Prepare
        var jvmType = new JvmType(JavaEmployee.class);

        // Execute
        var properties = jvmType.getOwnProperties();

        // Validate
        var worksAtProperty = properties.stream()
            .filter(p -> p.getName().equals("worksAt"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("worksAt property not found"));

        Map<String, String> metadata = worksAtProperty.getMetadata();
        assertNotNull(metadata);
        assertEquals(3, metadata.size());
        assertEquals("works at", metadata.get("predicate"));
        assertEquals("employs", metadata.get("inverse"));
        assertEquals("is employed by, works for", metadata.get("aliases"));
    }

    @Test
    void extractsMetadataFromMultipleFields() {
        // Prepare
        var jvmType = new JvmType(JavaPerson.class);

        // Execute
        var properties = jvmType.getOwnProperties();

        // Validate city field
        var cityProperty = properties.stream()
            .filter(p -> p.getName().equals("city"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("city property not found"));

        Map<String, String> cityMetadata = cityProperty.getMetadata();
        assertEquals("lives in", cityMetadata.get("predicate"));
        assertEquals("is home to", cityMetadata.get("inverse"));

        // Validate friend field
        var friendProperty = properties.stream()
            .filter(p -> p.getName().equals("friend"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("friend property not found"));

        Map<String, String> friendMetadata = friendProperty.getMetadata();
        assertEquals("knows", friendMetadata.get("predicate"));
        assertEquals("is known by", friendMetadata.get("inverse"));
    }

    @Test
    void emptyAnnotationReturnsEmptyMetadata() {
        // Prepare
        var jvmType = new JvmType(EmptyAnnotation.class);

        // Execute
        var properties = jvmType.getOwnProperties();

        // Validate
        var fieldProperty = properties.stream()
            .filter(p -> p.getName().equals("field"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("field property not found"));

        assertTrue(fieldProperty.getMetadata().isEmpty());
    }

    @Test
    void nonAnnotatedFieldHasEmptyMetadata() {
        // Prepare
        var jvmType = new JvmType(JavaEmployee.class);

        // Execute
        var properties = jvmType.getOwnProperties();

        // Validate - name field has no @Semantics annotation
        var nameProperty = properties.stream()
            .filter(p -> p.getName().equals("name"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("name property not found"));

        assertTrue(nameProperty.getMetadata().isEmpty());
    }

    @Test
    void worksWithNestedDomainTypes() {
        // Prepare
        var jvmType = new JvmType(JavaEmployee.class);

        // Execute
        var properties = jvmType.getOwnProperties();

        // Validate - worksAt should be a DomainTypePropertyDefinition with metadata
        var worksAtProperty = properties.stream()
            .filter(p -> p.getName().equals("worksAt"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("worksAt property not found"));

        assertInstanceOf(DomainTypePropertyDefinition.class, worksAtProperty);
        var domainProperty = (DomainTypePropertyDefinition) worksAtProperty;
        assertEquals("Company", domainProperty.getType().getOwnLabel());
        assertEquals("works at", domainProperty.getMetadata().get("predicate"));
    }
}
