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
package com.embabel.agent.autoconfigure.decision;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionModuleInventoryTest {
    @Test
    void reactorContainsThreeFrameworkNeutralLeavesAutoconfigureAndStarter() throws Exception {
        Path root = reactorRoot();
        String decisions = Files.readString(root.resolve("embabel-agent-decisions/pom.xml"));
        assertThat(decisions).contains(
                "<module>embabel-agent-decision</module>",
                "<module>embabel-agent-decision-typesafe</module>",
                "<module>embabel-agent-decision-llm</module>");
        assertThat(Files.readString(root.resolve("embabel-agent-autoconfigure/pom.xml")))
                .contains("<module>embabel-agent-decision-autoconfigure</module>");
        assertThat(Files.readString(root.resolve("embabel-agent-starters/pom.xml")))
                .contains("<module>embabel-agent-starter-decision</module>");
        assertThat(Files.exists(root.resolve("embabel-agent-starters/embabel-agent-starter-decision/src/main"))).isFalse();
    }

    private Path reactorRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null) {
            if (Files.exists(path.resolve("embabel-agent-decisions/pom.xml"))) return path;
            path = path.getParent();
        }
        throw new AssertionError("reactor root not found");
    }
}
