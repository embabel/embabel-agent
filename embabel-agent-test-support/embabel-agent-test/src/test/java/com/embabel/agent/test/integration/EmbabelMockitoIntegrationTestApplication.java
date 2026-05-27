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
package com.embabel.agent.test.integration;

import com.embabel.agent.prompt.persona.PersonaSpec;
import com.embabel.agent.prompt.persona.RoleGoalBackstorySpec;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Small SpringBootApplication needed to bootstrap the integration tests in this module.
 */
@SpringBootApplication
class EmbabelMockitoIntegrationTestApplication {
    public static void main(String[] args) {
        SpringApplication.run(EmbabelMockitoIntegrationTestApplication.class, args);
    }

    abstract static class Personas {
        static final RoleGoalBackstorySpec WRITER = RoleGoalBackstorySpec
                .withRole("Creative Storyteller")
                .andGoal("Write engaging and imaginative stories")
                .andBackstory("Has a PhD in French literature; used to work in a circus");

        static final PersonaSpec REVIEWER = PersonaSpec.create(
                "Media Book Review",
                "New York Times Book Reviewer",
                "Professional and insightful",
                "Help guide readers toward good stories"
        );
    }
}