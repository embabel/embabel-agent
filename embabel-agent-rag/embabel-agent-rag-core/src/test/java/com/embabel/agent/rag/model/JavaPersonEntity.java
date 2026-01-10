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

import java.util.List;
import java.util.stream.Collectors;

/**
 * Java interface for testing relationship navigation and business logic methods.
 */
public interface JavaPersonEntity extends NamedEntity {

    int getAge();

    @Relationship(name = "HAS_ADDRESS")
    List<JavaAddressEntity> getAddresses();

    @Relationship  // Defaults to HAS_EMPLOYER
    JavaCompanyEntity getEmployer();

    /**
     * Business logic method that invokes relationship method.
     */
    default String addressHistory() {
        List<JavaAddressEntity> addresses = getAddresses();
        return addresses.stream()
                .map(a -> a.getStreet() + ", " + a.getCity())
                .collect(Collectors.joining(" -> "));
    }

    /**
     * Business logic method using single relationship.
     */
    default String employerName() {
        JavaCompanyEntity employer = getEmployer();
        return employer != null ? employer.getName() : "Unemployed";
    }
}
