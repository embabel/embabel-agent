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
package com.embabel.agent.rag.service;

import com.embabel.agent.core.DataDictionary;
import com.embabel.agent.rag.model.*;
import com.embabel.agent.rag.service.support.InMemoryNamedEntityDataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that relationship navigation works correctly when entities are loaded
 * via the repository's findById method.
 */
public class JavaRepositoryRelationshipNavigationTest {

    private InMemoryNamedEntityDataRepository repository;

    @BeforeEach
    void setUp() {
        DataDictionary dataDictionary = DataDictionary.fromClasses(
                "test",
                JavaPersonEntity.class,
                JavaAddressEntity.class,
                JavaCompanyEntity.class
        );
        repository = new InMemoryNamedEntityDataRepository(dataDictionary);
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
    void relationshipNavigationWorksViaRepositoryFindById() {
        // Create and save entities
        NamedEntityData personData = createPerson("p1", "John", 30);
        NamedEntityData address1 = createAddress("a1", "123 Main St", "Springfield");
        NamedEntityData address2 = createAddress("a2", "456 Oak Ave", "Shelbyville");

        repository.save(personData);
        repository.save(address1);
        repository.save(address2);

        // Create relationships
        repository.createRelationship(
                new RetrievableIdentifier("p1", "JavaPersonEntity"),
                new RetrievableIdentifier("a1", "JavaAddressEntity"),
                new RelationshipData("HAS_ADDRESS", Map.of())
        );
        repository.createRelationship(
                new RetrievableIdentifier("p1", "JavaPersonEntity"),
                new RetrievableIdentifier("a2", "JavaAddressEntity"),
                new RelationshipData("HAS_ADDRESS", Map.of())
        );

        // Load person via repository - should have navigator attached
        JavaPersonEntity person = repository.findById("p1", JavaPersonEntity.class);
        assertNotNull(person);

        // Navigate relationship - should work!
        List<JavaAddressEntity> addresses = person.getAddresses();
        assertNotNull(addresses, "Relationship collection should not be null");
        assertEquals(2, addresses.size(), "Should have 2 addresses");
        assertEquals("123 Main St", addresses.get(0).getStreet());
        assertEquals("456 Oak Ave", addresses.get(1).getStreet());
    }

    @Test
    void singleRelationshipNavigationWorksViaRepository() {
        NamedEntityData personData = createPerson("p1", "John", 30);
        NamedEntityData company = createCompany("c1", "Acme Corp", "Technology");

        repository.save(personData);
        repository.save(company);

        repository.createRelationship(
                new RetrievableIdentifier("p1", "JavaPersonEntity"),
                new RetrievableIdentifier("c1", "JavaCompanyEntity"),
                new RelationshipData("HAS_EMPLOYER", Map.of())
        );

        JavaPersonEntity person = repository.findById("p1", JavaPersonEntity.class);
        assertNotNull(person);

        JavaCompanyEntity employer = person.getEmployer();
        assertNotNull(employer, "Single relationship should not be null when target exists");
        assertEquals("Acme Corp", employer.getName());
        assertEquals("Technology", employer.getIndustry());
    }

    @Test
    void emptyCollectionRelationshipReturnsEmptyListNotNull() {
        NamedEntityData personData = createPerson("p1", "John", 30);
        repository.save(personData);

        JavaPersonEntity person = repository.findById("p1", JavaPersonEntity.class);
        assertNotNull(person);

        // No addresses were created, should return empty list, not null
        List<JavaAddressEntity> addresses = person.getAddresses();
        assertNotNull(addresses, "Empty relationship should return empty list, not null");
        assertTrue(addresses.isEmpty(), "Should be empty");
    }

    @Test
    void nullableSingleRelationshipReturnsNull() {
        NamedEntityData personData = createPerson("p1", "John", 30);
        repository.save(personData);

        JavaPersonEntity person = repository.findById("p1", JavaPersonEntity.class);
        assertNotNull(person);

        // No employer was set
        JavaCompanyEntity employer = person.getEmployer();
        assertNull(employer, "Nullable relationship with no target should return null");
    }

    @Test
    void businessLogicMethodWorksWithRelationshipNavigation() {
        NamedEntityData personData = createPerson("p1", "John", 30);
        NamedEntityData address1 = createAddress("a1", "123 Main St", "Springfield");
        NamedEntityData address2 = createAddress("a2", "456 Oak Ave", "Shelbyville");

        repository.save(personData);
        repository.save(address1);
        repository.save(address2);

        repository.createRelationship(
                new RetrievableIdentifier("p1", "JavaPersonEntity"),
                new RetrievableIdentifier("a1", "JavaAddressEntity"),
                new RelationshipData("HAS_ADDRESS", Map.of())
        );
        repository.createRelationship(
                new RetrievableIdentifier("p1", "JavaPersonEntity"),
                new RetrievableIdentifier("a2", "JavaAddressEntity"),
                new RelationshipData("HAS_ADDRESS", Map.of())
        );

        JavaPersonEntity person = repository.findById("p1", JavaPersonEntity.class);
        assertNotNull(person);

        // Business logic method that internally calls getAddresses()
        String history = person.addressHistory();
        assertEquals("123 Main St, Springfield -> 456 Oak Ave, Shelbyville", history);
    }

    @Test
    void businessLogicMethodHandlesNullRelationship() {
        NamedEntityData personData = createPerson("p1", "John", 30);
        repository.save(personData);

        JavaPersonEntity person = repository.findById("p1", JavaPersonEntity.class);
        assertNotNull(person);

        // employerName() with no employer should return "Unemployed"
        String name = person.employerName();
        assertEquals("Unemployed", name);
    }
}
