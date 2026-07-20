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
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link ProcessSkillScriptExecutionEngine} from Java.
 *
 * <p>The engine's primary constructor takes a {@code kotlin.time.Duration} (an inline
 * value class), so without {@code @JvmOverloads} the only public constructors are
 * name-mangled synthetic ones that {@code javac} refuses — this class would not compile.
 */
class ProcessSkillScriptExecutionEngineJavaTest {

    @Test
    void constructableFromJavaWithDefaults() {
        ProcessSkillScriptExecutionEngine engine = new ProcessSkillScriptExecutionEngine();

        assertNotNull(engine);
        // A real engine (unlike NoOpExecutionEngine) supports at least one language.
        assertFalse(engine.supportedLanguages().isEmpty());
    }

    /**
     * The point of {@code confinedTo(root)} is to let a Java caller choose the
     * confinement root per request. This drives the engine end-to-end to prove the root
     * is actually honoured — not merely that the factory returns an engine: an input
     * <em>under</em> the given root is accepted, an input <em>outside</em> it is denied.
     *
     * <p>The accepted-input assertion is what makes this discriminating: both files live
     * under JUnit temp dirs, i.e. outside the engine's default root ({@code user.dir}).
     * If {@code confinedTo} ignored our root, the in-root input would be denied too and
     * this test would fail.
     */
    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void confinedToHonoursTheGivenRoot(@TempDir Path root, @TempDir Path outsideRoot) throws IOException {
        // A skill script that echoes whatever is staged into INPUT_DIR.
        Path scriptsDir = root.resolve("scripts");
        Files.createDirectories(scriptsDir);
        Path scriptFile = scriptsDir.resolve("cat.sh");
        Files.writeString(scriptFile, "#!/bin/bash\ncat \"$INPUT_DIR\"/*\n");
        scriptFile.toFile().setExecutable(true);
        SkillScript script = new SkillScript("test-skill", "cat.sh", ScriptLanguage.BASH, root);

        ProcessSkillScriptExecutionEngine engine =
                ProcessSkillScriptExecutionEngine.confinedTo(root.toString());

        // Input INSIDE the configured root -> accepted.
        Path inside = root.resolve("inside.txt");
        Files.writeString(inside, "OK");
        ScriptExecutionResult allowed = engine.execute(script, List.of(), null, List.of(inside));
        assertTrue(allowed instanceof ScriptExecutionResult.Success,
                "in-root input must be allowed, got: " + allowed);

        // Input OUTSIDE the configured root (a sibling @TempDir) -> denied.
        Path secret = outsideRoot.resolve("secret.txt");
        Files.writeString(secret, "TOP_SECRET");
        ScriptExecutionResult denied = engine.execute(script, List.of(), null, List.of(secret));
        assertTrue(denied instanceof ScriptExecutionResult.Denied,
                "out-of-root input must be denied, got: " + denied);
    }
}
