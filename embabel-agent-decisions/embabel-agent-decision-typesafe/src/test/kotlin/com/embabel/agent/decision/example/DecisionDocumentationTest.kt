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
package com.embabel.agent.decision.example

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DecisionDocumentationTest {
    private val root: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("embabel-agent-docs")) && Files.exists(it.resolve("embabel-agent-decisions")) }

    @Test
    fun `decision reference includes compiled samples complete properties and architecture`() {
        val reference = read("embabel-agent-docs/src/main/asciidoc/reference/reference.adoc")
        val page = read("embabel-agent-docs/src/main/asciidoc/reference/decisions/page.adoc")
        val modules = read("embabel-agent-docs/src/main/asciidoc/modules/page.adoc")
        val pom = read("embabel-agent-docs/pom.xml")
        assertThat(reference).contains("include::decisions/page.adoc[]")
        assertThat(page).contains("[[reference.decisions]]", "[[reference.decisions.promotion]]", "==== Promotion checkpoint")
        assertThat(page).contains("tag=kotlin-consumer", "tag=java-consumer", "tag=dice-consumer")
        listOf("TypeSafeDecisionModel", "PromptedDecisionModel", "NoDecisionModel", "StubDecisionModel").forEach { assertThat(page).contains(it) }
        assertThat(page).doesNotContain("DroolsDecisionModel", "CamundaDecisionModel", "TimefoldDecisionModel")
        val properties = listOf(
            "enabled", "provider", "default-timeout", "record-mode", "full-record-max-bytes", "record-allowlist",
            "mapper-bean-name", "typesafe.model", "typesafe.base-url", "typesafe.connect-timeout", "typesafe.api-key",
            "prompted.llm-bean-name", "prompted.options-bean-name",
        )
        properties.forEach { assertThat(page).contains("embabel.agent.decision.$it") }
        listOf("embabel-agent-decision`", "embabel-agent-decision-typesafe`", "embabel-agent-decision-llm`",
            "embabel-agent-decision-autoconfigure`", "embabel-agent-starter-decision`").forEach { assertThat(modules).contains(it) }
        assertThat(page).contains("[graphviz, decision-modules.dot, png]", "include::../diagrams/decision-modules.dot[]")
        assertThat(page).contains("[graphviz, decision-flow.dot, png]", "include::../diagrams/decision-flow.dot[]")
        val moduleDiagram = read("embabel-agent-docs/src/main/asciidoc/reference/diagrams/decision-modules.dot")
        assertThat(moduleDiagram).contains("starter -> auto", "starter -> platform", "embabel-agent-starter-platform")
        assertThat(page).contains(
            "-pl embabel-agent-dependencies install -DskipTests",
            "-am install -DskipTests",
            "-Ddecision.integration-profile=true",
        )
        assertThat(pom).contains("embabel-agent-decision/src/main/kotlin", "embabel-agent-decision-typesafe/src/main/kotlin",
            "embabel-agent-decision-llm/src/main/kotlin", "embabel-agent-decision-autoconfigure/src/main/java",
            "embabel-agent-decision-typesafe/src/test/kotlin", "embabel-agent-decision-typesafe/src/test/java")
        assertThat(read("embabel-agent-decisions/embabel-agent-decision-typesafe/src/test/kotlin/com/embabel/agent/decision/example/JevDecisionExample.kt"))
            .contains("tag::kotlin-consumer[]", "end::kotlin-consumer[]")
        assertThat(read("embabel-agent-decisions/embabel-agent-decision-typesafe/src/test/java/com/embabel/agent/decision/example/JevDecisionExample.java"))
            .contains("tag::java-consumer[]", "end::java-consumer[]")
        assertThat(read("embabel-agent-decisions/embabel-agent-decision-typesafe/src/test/java/com/embabel/agent/decision/example/DicePropositionRevisionExample.java"))
            .contains("tag::dice-consumer[]", "end::dice-consumer[]")
    }

    @Test
    fun `rendered decision guide and Dokka output are nonempty when requested`() {
        if (System.getProperty("decision.docs.rendered") != "true") return
        val guide = root.resolve("embabel-agent-docs/target/generated-docs/index.html")
        assertThat(guide).exists()
        val html = Files.readString(guide)
        assertThat(html).contains("reference.decisions", "Explicit Jev consumers", "Spring Boot configuration",
            "decision-modules.dot", "decision-flow.dot")
        assertThat(html).doesNotContain("Unresolved directive", "include::")
        listOf("decision-modules.dot.png", "decision-flow.dot.png").forEach { imageName ->
            val image = root.resolve("embabel-agent-docs/target/generated-docs/images/$imageName")
            assertThat(image).isRegularFile()
            assertThat(Files.size(image)).isGreaterThan(0)
        }
        val dokka = root.resolve("embabel-agent-docs/target/dokka-aggregate")
        assertThat(dokka).isDirectory()
        val files = Files.walk(dokka).use { stream -> stream.filter(Files::isRegularFile).toList() }
        assertThat(files).isNotEmpty()
        val joined = files.filter { it.toString().endsWith(".html") }.take(200)
            .joinToString("\n") { Files.readString(it) }
        assertThat(joined).contains("com.embabel.agent.decision")
        assertThat(joined).contains("com.embabel.agent.autoconfigure.decision")
    }

    private fun read(path: String): String = Files.readString(root.resolve(path))
}
