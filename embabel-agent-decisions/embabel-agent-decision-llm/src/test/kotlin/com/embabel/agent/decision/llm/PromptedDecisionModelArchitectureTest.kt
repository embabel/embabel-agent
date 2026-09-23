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
package com.embabel.agent.decision.llm

import com.embabel.agent.decision.DecisionModel
import com.embabel.agent.decision.NoDecisionModel
import com.embabel.agent.decision.StubDecisionModel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class PromptedDecisionModelArchitectureTest {
    @Test
    fun `exactly four named products construct the same final facade`() {
        val compiledProducts = listOf(
            PromptedDecisionModel::class.java,
            NoDecisionModel::class.java,
            StubDecisionModel::class.java,
        )
        val typeSafeSource = repositoryRoot().resolve(
            "embabel-agent-decisions/embabel-agent-decision-typesafe/src/main/kotlin/" +
                "com/embabel/agent/decision/typesafe/TypeSafeDecisionModel.kt",
        )
        val typeSafeText = Files.readString(typeSafeSource)

        assertThat(compiledProducts.map { it.simpleName } + "TypeSafeDecisionModel").containsExactly(
            "PromptedDecisionModel",
            "NoDecisionModel",
            "StubDecisionModel",
            "TypeSafeDecisionModel",
        )
        assertThat(compiledProducts).allSatisfy { product ->
            assertThat(product.methods.filter { it.name == "create" }).isNotEmpty
            assertThat(product.methods.filter { it.name == "create" })
                .allMatch { Modifier.isStatic(it.modifiers) && it.returnType == DecisionModel::class.java }
        }
        assertThat(typeSafeText).contains(
            "@ApiStatus.Experimental",
            "object TypeSafeDecisionModel",
            "fun create(",
            "): DecisionModel",
            "return DecisionModel(",
        )
        assertThat(Modifier.isFinal(DecisionModel::class.java.modifiers)).isTrue()
        assertThat(PromptedDecisionModel::class.java.declaredConstructors).allMatch { Modifier.isPrivate(it.modifiers) }
        assertThat(PromptedDecisionModel::class.java.declaredMethods.filter { Modifier.isPublic(it.modifiers) })
            .allMatch { it.name == "create" && it.returnType == DecisionModel::class.java }
            .hasSize(2)
    }

    @Test
    fun `compiled adapter has no parser conversion logging projection tools or orchestration calls`() {
        val classes = listOf(
            PromptedDecisionModel::class.java,
            Class.forName("com.embabel.agent.decision.llm.PromptedProvider"),
            Class.forName("com.embabel.agent.decision.llm.PromptedWireCodec"),
        ).flatMap(::nestedClasses)
        val forbiddenClassTokens = listOf(
            "com/embabel/agent/core/Ai",
            "AgentProcess",
            "ModelProvider",
            "PromptRunner",
            "org/slf4j/Logger",
            "LoggerFactory",
            "convertValue",
            "valueToTree",
            "treeToValue",
        )

        classes.forEach { type ->
            val bytes = requireNotNull(type.getResourceAsStream("/${type.name.replace('.', '/')}.class")) {
                "missing bytecode for ${type.name}"
            }.use { it.readAllBytes() }
            val constantPoolText = String(bytes, StandardCharsets.ISO_8859_1)
            forbiddenClassTokens.forEach { token ->
                assertThat(constantPoolText).describedAs("${type.name} must not reference $token").doesNotContain(token)
            }

            val disassembly = javap(type)
            assertThat(disassembly).doesNotContain(
                "JacksonOutputConverter.convert:",
                "StructuredOutputConverter.convert:",
                "Tool.call:",
            )
        }
        assertThat(PromptedDecisionModel::class.java.declaredClasses.map { it.simpleName })
            .containsExactly("Companion")
    }

    private fun nestedClasses(root: Class<*>): List<Class<*>> =
        listOf(root) + root.declaredClasses.flatMap(::nestedClasses)

    private fun repositoryRoot(): Path {
        var candidate = Path.of("").toAbsolutePath()
        while (!Files.exists(candidate.resolve("embabel-agent-decisions/pom.xml"))) {
            candidate = candidate.parent ?: error("cannot locate repository root")
        }
        return candidate
    }

    private fun javap(type: Class<*>): String {
        val process = ProcessBuilder(
            "javap",
            "-classpath",
            System.getProperty("java.class.path"),
            "-c",
            "-p",
            type.name,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertThat(process.waitFor()).describedAs("javap ${type.name}: $output").isZero()
        return output
    }
}
