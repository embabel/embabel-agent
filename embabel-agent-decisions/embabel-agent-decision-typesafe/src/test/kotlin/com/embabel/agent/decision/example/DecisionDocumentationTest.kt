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
        assertThat(page).contains(
            "Decisions are the experimental third Embabel model type",
            "Four built-in products ship in this release",
            "Custom implementations are extension adapters",
            "A nonempty `models` map replaces implicit Jev completely",
            "embabel.models.default-decision-model",
            "compiled and executed by the TypeSafe example tests",
            "`StubDecisionModel` is test-only",
        )
        assertThat(page).contains("tag=custom-provider", "tag=kotlin-consumer", "tag=java-consumer", "tag=dice-consumer")
        listOf("TypeSafeDecisionModel", "PromptedDecisionModel", "NoDecisionModel", "StubDecisionModel").forEach { assertThat(page).contains(it) }
        assertThat(page).doesNotContain("DroolsDecisionModel", "CamundaDecisionModel", "TimefoldDecisionModel")
        val properties = listOf(
            "enabled", "models.jev.provider", "default-timeout", "record-mode", "full-record-max-bytes", "record-allowlist",
            "mapper-bean-name", "models.jev.typesafe.model", "models.jev.typesafe.base-url",
            "models.jev.typesafe.connect-timeout", "models.prompted-review.prompted.llm-bean-name",
            "models.prompted-review.prompted.options-bean-name",
        )
        properties.forEach { assertThat(page).contains("embabel.agent.platform.decision.$it") }
        listOf("embabel-agent-decision`", "embabel-agent-decision-typesafe`", "embabel-agent-decision-llm`",
            "embabel-agent-decision-autoconfigure`", "embabel-agent-starter-decision`").forEach { assertThat(modules).contains(it) }
        assertThat(page).contains("[graphviz, decision-modules.dot, png]", "include::../diagrams/decision-modules.dot[]")
        assertThat(page).contains("[graphviz, decision-flow.dot, png]", "include::../diagrams/decision-flow.dot[]")
        val moduleDiagram = read("embabel-agent-docs/src/main/asciidoc/reference/diagrams/decision-modules.dot")
        assertThat(moduleDiagram).contains(
            "starter -> auto", "starter -> platform", "embabel-agent-starter-platform",
            "api -> core", "api -> ai", "ModelProvider", "ModelSelectionCriteria", "named DecisionModel beans",
            "DecisionInstrumentation SPI", "optional Micrometer adapter",
            "DecisionExecutionContext", "ConfigurableModelProvider", "AgentProcess + model-selection bridge",
        )
        assertThat(moduleDiagram).doesNotContain("auto -> api", "core -> ai")
        val flowDiagram = read("embabel-agent-docs/src/main/asciidoc/reference/diagrams/decision-flow.dot")
        assertThat(flowDiagram).contains(
            "ModelSelectionCriteria", "ModelProvider", "default / name / role", "per-call provenance",
            "host-owned action or proposition revision", "direct construction", "typed evidence + provenance",
            "finite provider events", "structured terminal log", "optional telemetry adapter",
            "DecisionExecutionContext", "AgentProcess + model selection", "Micrometer context",
        )
        assertThat(page).contains(
            "requested model `jev-latest`", "registry name `jev`", "provider `typesafe`",
            "EMBABEL_AGENT_PLATFORM_DECISION_MODELS_PROPOSITIONREVISION_PROVIDER",
            "TYPESAFE_API_KEY is required; no network request was made",
            "hyphens in registry names cannot be represented faithfully",
            "registry metadata", "per-call provenance", "Dice retains action ownership",
            "required for every explicit declaration", "required for every explicit `typesafe` declaration",
            "custom `DecisionModel` bean",
            "platform default, automatic selection, registry name, role, ordered fallback, random choice, or a pre-resolved model",
        )
        val legacyDecisionPrefix = "embabel.agent." + "decision"
        val legacyDecisionEnvironmentPrefix = "EMBABEL_AGENT_" + "DECISION_"
        assertThat(page).doesNotContain(
            "Automatic `Ai` or `ModelProvider` selection is deferred",
            "$legacyDecisionPrefix.provider",
            "$legacyDecisionPrefix.typesafe.model",
            "$legacyDecisionPrefix.enabled",
            legacyDecisionEnvironmentPrefix,
        )
        assertThat(page).contains(
            "embabel.decision",
            "embabel.decision.duration",
            "embabel.decision.calls.total",
            "embabel.decision.keys.total",
            "embabel.decision.events.total",
            "provider.family", "safe.code", "key.outcome", "trace-decisions", "metrics-decisions",
            "Explicit instrumentation remains authoritative",
            "Prompted attempts count `sender.call` invocations",
            "four worker slots",
            "best-effort cancellation",
            "Decision telemetry never records decision content",
            "<T> Callable<T> wrap(Callable<T>)", "DecisionModel.installExecutionContext(context)",
            "first installation wins", "portable identity context", "registered and pre-resolved selection paths",
            "Micrometer `ContextSnapshot`",
            "independently of decision instrumentation",
        )
        assertThat(page).contains(
            "-pl embabel-agent-dependencies install -DskipTests",
            "-am install -DskipTests",
            "-Pintegration-tests -Ddecision.live=true",
        )
        assertThat(page).doesNotContain("-Ddecision.integration-profile=true")
        val promotion = page.substringAfter("[[reference.decisions.promotion]]")
        assertThat(promotion).contains(
            "Project maintainers own promotion",
            "release immediately after the first experimental release",
            "repository-observable checkpoint",
            "full reactor verification",
        )
        assertThat(promotion).doesNotContain("James", "Opus", "Jev", "Fable", "Claude", "Codex", "Astra", "GEV")
        assertThat(pom).contains("embabel-agent-decision/src/main/kotlin", "embabel-agent-decision/src/main/java",
            "embabel-agent-decision-typesafe/src/main/kotlin", "embabel-agent-decision-llm/src/main/kotlin",
            "embabel-agent-observability/src/main/java", "embabel-agent-decision-autoconfigure/src/main/java",
            "embabel-agent-decision-typesafe/src/test/kotlin", "embabel-agent-decision-typesafe/src/test/java")
        val kotlinExample = read("embabel-agent-decisions/embabel-agent-decision-typesafe/src/test/kotlin/com/embabel/agent/decision/example/JevDecisionExample.kt")
        assertThat(kotlinExample).contains(
                "tag::kotlin-consumer[]", "end::kotlin-consumer[]",
                "tag::kotlin-selection[]", "end::kotlin-selection[]",
                "PlatformDefault", "Auto", "byName", "byRole", "firstOf", "randomOf", "preResolved",
                "System.getenv(\"TYPESAFE_API_KEY\")?.takeIf(String::isNotBlank)",
                "System.getenv(\"TYPESAFE_MODEL\")?.takeIf(String::isNotBlank)",
            )
        val javaExample = read("embabel-agent-decisions/embabel-agent-decision-typesafe/src/test/java/com/embabel/agent/decision/example/JevDecisionExample.java")
        assertThat(javaExample).contains(
                "tag::java-consumer[]", "end::java-consumer[]",
                "tag::java-selection[]", "end::java-selection[]",
                "PlatformDefault", "Auto", "byName", "byRole", "firstOf", "randomOf", "preResolved",
                "requiredEnvironment(\"TYPESAFE_API_KEY\")",
                "if (value == null || value.isBlank())",
            )
        assertThat(read("embabel-agent-decisions/embabel-agent-decision-typesafe/src/test/java/com/embabel/agent/decision/example/DicePropositionRevisionExample.java"))
            .contains(
                "tag::dice-consumer[]", "end::dice-consumer[]", "ModelProvider", "ModelSelectionCriteria",
                "Dice is a consumer project that uses typed relation evidence for proposition revision",
            )
        assertThat(read("embabel-agent-decisions/embabel-agent-decision/src/test/kotlin/example/decision/provider/CustomDecisionProviderExample.kt"))
            .contains("tag::custom-provider[]", "end::custom-provider[]")
    }

    @Test
    fun `rendered decision guide contains current samples and Dokka output when requested`() {
        if (System.getProperty("decision.docs.rendered") != "true") return
        val guide = root.resolve("embabel-agent-docs/target/generated-docs/index.html")
        assertThat(guide).exists()
        val html = Files.readString(guide)
        assertThat(html).contains("reference.decisions", "Provider SPI", "Explicit Jev consumers", "Spring Boot configuration",
            "decision-modules.dot", "decision-flow.dot")
        assertThat(html).doesNotContain("Unresolved directive", "include::")
        val renderedText = normalizeRenderedHtml(html)
        listOf(
            "embabel-agent-decisions/embabel-agent-decision/src/test/kotlin/example/decision/provider/CustomDecisionProviderExample.kt" to "custom-provider",
            "embabel-agent-decisions/embabel-agent-decision-typesafe/src/test/kotlin/com/embabel/agent/decision/example/JevDecisionExample.kt" to "kotlin-consumer",
            "embabel-agent-decisions/embabel-agent-decision-typesafe/src/test/kotlin/com/embabel/agent/decision/example/JevDecisionExample.kt" to "kotlin-selection",
            "embabel-agent-decisions/embabel-agent-decision-typesafe/src/test/java/com/embabel/agent/decision/example/JevDecisionExample.java" to "java-consumer",
            "embabel-agent-decisions/embabel-agent-decision-typesafe/src/test/java/com/embabel/agent/decision/example/JevDecisionExample.java" to "java-selection",
            "embabel-agent-decisions/embabel-agent-decision-typesafe/src/test/java/com/embabel/agent/decision/example/DicePropositionRevisionExample.java" to "dice-consumer",
        ).forEach { (source, tag) ->
            assertThat(renderedText.contains(normalize(taggedSource(source, tag))))
                .describedAs("rendered guide must contain the current $tag tagged source")
                .isTrue()
        }
        listOf("decision-modules.dot.png", "decision-flow.dot.png").forEach { imageName ->
            val image = root.resolve("embabel-agent-docs/target/generated-docs/images/$imageName")
            assertThat(image).isRegularFile()
            assertThat(Files.size(image)).isGreaterThan(0)
        }
        val dokka = root.resolve("embabel-agent-docs/target/dokka-aggregate")
        assertThat(dokka).isDirectory()
        val files = Files.walk(dokka).use { stream -> stream.filter(Files::isRegularFile).toList() }
        assertThat(files).isNotEmpty()
        val htmlPages = files.filter { it.toString().endsWith(".html") }.map(Files::readString)
        listOf(
            "DecisionExecutionContext", "DecisionInstrumentation", "DecisionRequest", "DecisionOutcome",
            "DecisionMicrometerInstrumentation", "com.embabel.agent.autoconfigure.decision",
        ).forEach { publicApi ->
            assertThat(htmlPages.any { it.contains(publicApi) })
                .describedAs("Dokka output must contain $publicApi")
                .isTrue()
        }
    }

    private fun taggedSource(path: String, tag: String): String {
        val lines = read(path).lineSequence().toList()
        val start = lines.indexOfFirst { it.contains("tag::$tag[]") }
        val end = lines.indexOfFirst { it.contains("end::$tag[]") }
        require(start >= 0 && end > start) { "missing or invalid $tag source markers in $path" }
        return lines.subList(start + 1, end)
            .filterNot { it.contains("tag::") || it.contains("end::") }
            .joinToString("\n")
    }

    private fun normalizeRenderedHtml(html: String): String = normalize(
        html.replace(Regex("<[^>]+>"), " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
            .replace(Regex("&#(\\d+);")) { match -> match.groupValues[1].toInt().toChar().toString() },
    )

    private fun normalize(value: String): String = value.replace(Regex("\\s+"), "")

    private fun read(path: String): String = Files.readString(root.resolve(path))
}
