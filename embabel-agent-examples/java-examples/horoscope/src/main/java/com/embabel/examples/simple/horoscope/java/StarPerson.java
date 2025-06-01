/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.examples.simple.horoscope.java;

import com.embabel.agent.domain.library.Person;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("Person with astrology details")
public record StarPerson(
    String name,
    @JsonPropertyDescription("Star sign") String sign
) implements Person {
    
    @JsonCreator
    public StarPerson(
            @JsonProperty("name") String name,
            @JsonProperty("sign") String sign
    ) {
        this.name = name;
        this.sign = sign;
    }
    
    @Override
    public String getName() {
        return name;
    }
}