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
package com.embabel.agent.api.tool

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Verifies that [DelegatingTool] implementors follow the canonical call pattern:
 * override [Tool.call] (String, ToolCallContext) only, never [Tool.call] (String).
 *
 * [DelegatingTool.call] (String) routes to `call(String, ToolCallContext.EMPTY)` so that
 * the two-arg method is the single entry point for decorator logic. If a decorator
 * overrides the single-arg version instead, [com.embabel.agent.spi.loop.support.DefaultToolLoop]
 * (which calls the two-arg version) will bypass the decorator's behavior silently.
 *
 * @see DelegatingTool
 */
class DelegatingToolArchitectureTest {

    @Test
    fun `DelegatingTool implementors must not override single-arg call`() {
        val classes = ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("com.embabel")

        val violations = classes
            .filter { it.isAssignableTo(DelegatingTool::class.java) }
            .filter { !it.isInterface }
            .filter { javaClass ->
                javaClass.getMethods().any { method ->
                    method.getName() == "call"
                        && method.getRawParameterTypes().size == 1
                        && method.getRawParameterTypes()[0].isEquivalentTo(String::class.java)
                        && method.getOwner() == javaClass
                }
            }
            .map { it.getName() }

        assertThat(violations)
            .describedAs(
                "These DelegatingTool implementations override call(String), which is " +
                    "bypassed by DefaultToolLoop. Override call(String, ToolCallContext) " +
                    "instead — see DelegatingTool KDoc.\n  " +
                    violations.joinToString("\n  ")
            )
            .isEmpty()
    }
}
