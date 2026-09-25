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
package com.embabel.common.ai.classification;

import static org.junit.jupiter.api.Assertions.*;

import com.embabel.common.ai.decision.PropositionRequest;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.model.*;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

class ClassificationJavaTest {
    enum AnimalKind {
        DOG,
        CAT,
        RABBIT;

        @Override
        public String toString() {
            return "not an ID";
        }
    }

    interface Animal {}

    static final class Dog implements Animal {
        private Dog() {
            throw new AssertionError("No extraction");
        }
    }

    static final class Cat implements Animal {}

    static final class Rabbit implements Animal {}

    // tag::enum-mapping[]
    @Test
    void enumMapping() {
        var mapping =
                CategoryMapping.fromEnum(
                        AnimalKind.class,
                        animal ->
                                switch (animal) {
                                    case DOG -> "A dog or canine";
                                    case CAT -> "A cat or feline";
                                    case RABBIT -> "A rabbit or bunny";
                                });
        var request = mapping.request("A canine is barking");
        var result = request.selected("DOG", new ModelProvenance("model", "provider"));
        var mapped =
                assertInstanceOf(MappedClassificationResult.Selected.class, mapping.map(result));
        assertEquals(AnimalKind.DOG, mapped.getValue());
        assertNull(result.getConfidence());
    }

    // end::enum-mapping[]

    @Test
    void nonselectionIsUsableFromJava() {
        var mapping = CategoryMapping.fromEnum(AnimalKind.class, AnimalKind::name);
        var noMatch = new ClassificationResult.NoMatch(new ModelProvenance("model", "provider"));
        MappedClassificationResult<AnimalKind> mapped = mapping.map(noMatch);
        assertTrue(mapped instanceof ClassificationResult.NoMatch);
        assertSame(noMatch, mapped);
    }

    // tag::class-mapping[]
    @Test
    void classTokensRemainLocalValues() {
        var values = new LinkedHashMap<Category, Class<? extends Animal>>();
        values.put(new Category("dog", "A dog or canine"), Dog.class);
        values.put(new Category("cat", "A cat or feline"), Cat.class);
        values.put(new Category("rabbit", "A rabbit or bunny"), Rabbit.class);
        var mapping = new CategoryMapping<>(values);
        var request = mapping.request("A canine is barking");
        var result = request.selected("dog", new ModelProvenance("model", "provider"));
        var mapped =
                assertInstanceOf(MappedClassificationResult.Selected.class, mapping.map(result));
        assertSame(Dog.class, mapped.getValue());
    }

    // end::class-mapping[]

    // tag::proposition[]
    @Test
    void javaServiceImplementation() {
        DecisionService service =
                new DecisionService() {
                    public String getName() {
                        return "model";
                    }

                    public String getProvider() {
                        return "provider";
                    }

                    public ClassificationResult classify(ClassificationRequest request) {
                        return request.selected(
                                request.getCategories().getFirst().getId(),
                                new ModelProvenance(getName(), getProvider()));
                    }

                    public PropositionResult assess(PropositionRequest request) {
                        return new PropositionResult.Answered(
                                false, new ModelProvenance(getName(), getProvider()));
                    }
                };
        var result =
                assertInstanceOf(
                        PropositionResult.Answered.class,
                        service.assess(
                                new PropositionRequest(
                                        "The rabbit is resting", "The animal is a dog")));
        assertFalse(result.getAnswer());
        assertNull(result.getPTrue());
        ClassificationService classifier = service;
        assertEquals(ModelType.DECISION, classifier.getType());
        assertEquals(ModelType.DECISION, classifier.metadata().getType());
        assertEquals(
                ModelType.CLASSIFICATION,
                ClassificationServiceMetadata.create("model", "provider").getType());
        assertEquals(
                ModelType.DECISION, DecisionServiceMetadata.create("model", "provider").getType());
    }
    // end::proposition[]
}
