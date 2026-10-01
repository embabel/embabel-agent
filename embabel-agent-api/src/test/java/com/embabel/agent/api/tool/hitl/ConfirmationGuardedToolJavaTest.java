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
package com.embabel.agent.api.tool.hitl;

import com.embabel.agent.api.tool.Tool;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java interoperability tests for {@link ConfirmationGuardedTool#of}.
 * Behaviour is covered in ConfirmationGuardedToolTest; this checks the Java-facing API shape.
 */
class ConfirmationGuardedToolJavaTest {

    private Tool createTask() {
        return Tool.create("create_task", "Create a task", input -> Tool.Result.text("created"));
    }

    @Test
    void guardWithStaticMessage() {
        ConfirmationGuardedTool guard = ConfirmationGuardedTool.of(createTask(), "Create this task?");

        assertEquals("create_task", guard.getDefinition().getName());
        assertEquals(ConfirmationMode.ASK_VIA_LLM, guard.getMode());
        assertEquals("confirm_create_task", guard.getVerdictTool().getDefinition().getName());
        assertEquals(2, guard.tools().size());
    }

    @Test
    void guardWithMessageProviderAndMode() {
        Function<String, String> provider = input -> "Confirm " + input + "?";
        ConfirmationGuardedTool guard = ConfirmationGuardedTool.of(createTask(), provider, ConfirmationMode.PAUSE_PROCESS);

        assertEquals(ConfirmationMode.PAUSE_PROCESS, guard.getMode());
        assertSame(guard, guard.tools().get(0));
    }

    @Test
    void guardWithOptions() {
        ConfirmationGuardOptions options = ConfirmationGuardOptions.DEFAULT
                .withConfirmationNote("Check with the user first.")
                .withVerdictToolPrefix("approve_");

        ConfirmationGuardedTool guard = ConfirmationGuardedTool.of(createTask(), "Create this task?", ConfirmationMode.ASK_VIA_LLM, options);

        assertEquals("approve_create_task", guard.getVerdictTool().getDefinition().getName());
        assertTrue(guard.getDefinition().getDescription().endsWith("Check with the user first."));
        assertEquals(options, guard.getOptions());
    }

    @Test
    void callsOutsideAnAgentProcessFail() {
        ConfirmationGuardedTool guard = ConfirmationGuardedTool.of(createTask(), "Create this task?");

        assertThrows(IllegalStateException.class, () -> guard.call("{}"));
        assertThrows(IllegalStateException.class, () -> guard.getVerdictTool().call("{\"accepted\":true}"));
    }
}
