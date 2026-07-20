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
package com.embabel.agent.skills.script;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies that {@link DockerSkillScriptExecutionEngine} is constructable from Java,
 * including via the {@code confinedTo(root)} factory.
 *
 * <p>Constructing the engine and reading {@code supportedLanguages()} does not require
 * Docker (only {@code execute()} does), so these tests run without a Docker daemon.
 */
class DockerSkillScriptExecutionEngineJavaTest {

    @Test
    void constructableFromJavaWithDefaults() {
        DockerSkillScriptExecutionEngine engine = new DockerSkillScriptExecutionEngine();

        assertNotNull(engine);
        // A real engine (unlike NoOpExecutionEngine) supports at least one language.
        assertFalse(engine.supportedLanguages().isEmpty());
    }

    /**
     * The {@code confinedTo(root)} factory lets a Java caller set the confinement root
     * per request; this test would not compile if it were not Java-callable.
     *
     * <p>Scope: Java-callability only — driving confinement end-to-end needs a Docker
     * daemon, and the confinement itself goes through the same
     * {@code resolveInputFile} / {@code resolveAndValidateFile} path as the process
     * engine, whose behaviour is covered in {@code ProcessSkillScriptExecutionEngineJavaTest}.
     */
    @Test
    void confinedToFactoryIsCallableFromJava(@TempDir Path root) {
        DockerSkillScriptExecutionEngine engine =
                DockerSkillScriptExecutionEngine.confinedTo(root.toString());

        assertNotNull(engine);
        assertFalse(engine.supportedLanguages().isEmpty());
    }
}
