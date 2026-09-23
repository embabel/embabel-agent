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
package com.embabel.agent.decision;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionCoreClasspathTest {
    @Test
    void runtimeClosureIsFrameworkNeutral() throws Exception {
        Path runtime = Path.of("target/core-runtime");
        assertThat(runtime).isDirectory();
        List<String> artifacts;
        try (var files = Files.list(runtime)) {
            artifacts = files.map(path -> path.getFileName().toString()).sorted().toList();
        }
        assertThat(artifacts).isNotEmpty()
                .noneMatch(name -> name.startsWith("embabel-agent-ai-"))
                .noneMatch(name -> name.startsWith("spring-") || name.startsWith("spring-ai-"));
    }
}
