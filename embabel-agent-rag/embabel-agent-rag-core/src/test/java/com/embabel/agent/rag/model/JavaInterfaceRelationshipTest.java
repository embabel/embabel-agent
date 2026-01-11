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
package com.embabel.agent.rag.model;

import com.embabel.agent.rag.service.RetrievableIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests relationship navigation and business logic methods using Java interfaces.
 */
public class JavaInterfaceRelationshipTest {

    private TestNavigator navigator;

    @BeforeEach
    void setUp() {
        navigator = new TestNavigator();
    }

    private NamedEntityData createAddress(String id, String street, String city) {
        Set<String> labels = new HashSet<>();
        labels.add("JavaAddressEntity");
        Map<String, Object> properties = new HashMap<>();
        properties.put("street", street);
        properties.put("city", city);
        return new SimpleNamedEntityData(
            id,
            null,
            street + ", " + city,
            "Address",
            labels,
            properties,
            new HashMap<>(),
            null
        );
    }

    private NamedEntityData createCompany(String id, String name, String industry) {
        Set<String> labels = new HashSet<>();
        labels.add("JavaCompanyEntity");
        Map<String, Object> properties = new HashMap<>();
        properties.put("industry", industry);
        return new SimpleNamedEntityData(
            id,
            null,
            name,
            "A company",
            labels,
            properties,
            new HashMap<>(),
            null
        );
    }

    private NamedEntityData createPerson(String id, String name, int age) {
        Set<String> labels = new HashSet<>();
        labels.add("JavaPersonEntity");
        Map<String, Object> properties = new HashMap<>();
        properties.put("age", age);
        return new SimpleNamedEntityData(
            id,
            null,
            name,
            "A person",
            labels,
            properties,
            new HashMap<>(),
            null
        );
    }

    @Test
    void nullableRelationshipReturnsNullWhenNoRelatedEntity() {
        NamedEntityData personData = createPerson("p1", "John", 30);
        JavaPersonEntity person = personData.toInstance(navigator, JavaPersonEntity.class);

        // getEmployer() returns nullable type - should return null without error
        JavaCompanyEntity employer = person.getEmployer();
        assertNull(employer);
    }

    @Test
    void collectionRelationshipReturnsEmptyListWhenNoRelatedEntities() {
        NamedEntityData personData = createPerson("p1", "John", 30);
        JavaPersonEntity person = personData.toInstance(navigator, JavaPersonEntity.class);

        // getAddresses() returns List - should return empty list without error
        List<JavaAddressEntity> addresses = person.getAddresses();
        assertNotNull(addresses);
        assertTrue(addresses.isEmpty());
    }

    @Test
    void relationshipGetterReturnsList() {
        NamedEntityData personData = createPerson("p1", "John", 30);
        NamedEntityData address1 = createAddress("a1", "123 Main St", "Springfield");
        NamedEntityData address2 = createAddress("a2", "456 Oak Ave", "Shelbyville");

        navigator.addRelationship("p1", "HAS_ADDRESS", RelationshipDirection.OUTGOING, address1);
        navigator.addRelationship("p1", "HAS_ADDRESS", RelationshipDirection.OUTGOING, address2);

        JavaPersonEntity person = personData.toInstance(navigator, JavaPersonEntity.class);

        List<JavaAddressEntity> addresses = person.getAddresses();
        assertEquals(2, addresses.size());
        assertEquals("123 Main St", addresses.get(0).getStreet());
        assertEquals("456 Oak Ave", addresses.get(1).getStreet());
    }

    @Test
    void relationshipGetterReturnsSingleEntity() {
        NamedEntityData personData = createPerson("p1", "John", 30);
        NamedEntityData company = createCompany("c1", "Acme Corp", "Technology");

        navigator.addRelationship("p1", "HAS_EMPLOYER", RelationshipDirection.OUTGOING, company);

        JavaPersonEntity person = personData.toInstance(navigator, JavaPersonEntity.class);

        JavaCompanyEntity employer = person.getEmployer();
        assertNotNull(employer);
        assertEquals("Acme Corp", employer.getName());
        assertEquals("Technology", employer.getIndustry());
    }

    @Test
    void businessLogicMethodInvokesRelationshipMethod() {
        NamedEntityData personData = createPerson("p1", "John", 30);
        NamedEntityData address1 = createAddress("a1", "123 Main St", "Springfield");
        NamedEntityData address2 = createAddress("a2", "456 Oak Ave", "Shelbyville");

        navigator.addRelationship("p1", "HAS_ADDRESS", RelationshipDirection.OUTGOING, address1);
        navigator.addRelationship("p1", "HAS_ADDRESS", RelationshipDirection.OUTGOING, address2);

        JavaPersonEntity person = personData.toInstance(navigator, JavaPersonEntity.class);

        // addressHistory() internally calls getAddresses()
        String history = person.addressHistory();
        assertEquals("123 Main St, Springfield -> 456 Oak Ave, Shelbyville", history);
    }

    @Test
    void businessLogicMethodWithSingleRelationship() {
        NamedEntityData personData = createPerson("p1", "John", 30);
        NamedEntityData company = createCompany("c1", "Acme Corp", "Technology");

        navigator.addRelationship("p1", "HAS_EMPLOYER", RelationshipDirection.OUTGOING, company);

        JavaPersonEntity person = personData.toInstance(navigator, JavaPersonEntity.class);

        // employerName() internally calls getEmployer()
        String name = person.employerName();
        assertEquals("Acme Corp", name);
    }

    @Test
    void businessLogicMethodHandlesNullRelationship() {
        NamedEntityData personData = createPerson("p1", "John", 30);
        JavaPersonEntity person = personData.toInstance(navigator, JavaPersonEntity.class);

        // employerName() with no employer
        String name = person.employerName();
        assertEquals("Unemployed", name);
    }

    @Test
    void scalarPropertiesWork() {
        NamedEntityData personData = createPerson("p1", "John", 30);
        JavaPersonEntity person = personData.toInstance(navigator, JavaPersonEntity.class);

        assertEquals("p1", person.getId());
        assertEquals("John", person.getName());
        assertEquals(30, person.getAge());
    }

    /**
     * Test navigator implementation for testing.
     */
    static class TestNavigator implements RelationshipNavigator {
        private final Map<String, Map<String, List<NamedEntityData>>> relationships = new HashMap<>();

        void addRelationship(
            String entityId,
            String relationshipName,
            RelationshipDirection direction,
            NamedEntityData target
        ) {
            String key = entityId + ":" + relationshipName + ":" + direction;
            relationships
                .computeIfAbsent(key, k -> new HashMap<>())
                .computeIfAbsent(relationshipName, k -> new ArrayList<>())
                .add(target);
        }

        @Override
        public List<NamedEntityData> findRelated(
            RetrievableIdentifier source,
            String relationshipName,
            RelationshipDirection direction
        ) {
            String key = source.getId() + ":" + relationshipName + ":" + direction;
            Map<String, List<NamedEntityData>> rels = relationships.get(key);
            if (rels == null) {
                return List.of();
            }
            List<NamedEntityData> result = rels.get(relationshipName);
            return result != null ? result : List.of();
        }
    }
}
