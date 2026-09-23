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
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionModuleInventoryTest {
    private static final Set<String> DECISION_MODULES = Set.of(
            "embabel-agent-decision", "embabel-agent-decision-typesafe", "embabel-agent-decision-llm");
    private static final Set<String> MANAGED_ARTIFACTS = Set.of(
            "embabel-agent-decision", "embabel-agent-decision-typesafe", "embabel-agent-decision-llm",
            "embabel-agent-decision-autoconfigure", "embabel-agent-starter-decision");
    private static final Map<String, Set<Coordinate>> DIRECT_DEPENDENCIES = Map.of(
            "embabel-agent-decision", Set.of(new Coordinate("org.jetbrains.kotlin", "kotlin-stdlib")),
            "embabel-agent-decision-typesafe", Set.of(
                    new Coordinate("com.embabel.agent", "embabel-agent-decision"),
                    new Coordinate("com.embabel.agent", "embabel-agent-ai")),
            "embabel-agent-decision-llm", Set.of(
                    new Coordinate("com.embabel.agent", "embabel-agent-decision"),
                    new Coordinate("com.embabel.agent", "embabel-agent-api")),
            "embabel-agent-decision-autoconfigure", Set.of(
                    new Coordinate("com.embabel.agent", "embabel-agent-decision-typesafe"),
                    new Coordinate("com.embabel.agent", "embabel-agent-decision-llm"),
                    new Coordinate("org.springframework.boot", "spring-boot-autoconfigure")),
            "embabel-agent-starter-decision", Set.of(
                    new Coordinate("com.embabel.agent", "embabel-agent-starter-platform"),
                    new Coordinate("com.embabel.agent", "embabel-agent-decision-autoconfigure")));

    @Test
    void actualReactorAndDependencyGraphMatchesThePublishedDecisionArchitecture() throws Exception {
        DecisionGraph graph = readGraph(reactorRoot());
        assertThat(validate(graph)).isEmpty();
        assertThat(Files.exists(reactorRoot()
                .resolve("embabel-agent-starters/embabel-agent-starter-decision/src/main"))).isFalse();
    }

    @Test
    void graphContractRejectsAnExtraDecisionAggregateModule() throws Exception {
        DecisionGraph graph = readGraph(reactorRoot());
        Set<String> mutation = new LinkedHashSet<>(graph.decisionModules());
        mutation.add("embabel-agent-decision-rules");
        assertThat(validate(graph.withDecisionModules(mutation)))
                .contains("decision aggregate modules differ from the allowed set");
    }

    @Test
    void graphContractRejectsAMissingBomEntry() throws Exception {
        DecisionGraph graph = readGraph(reactorRoot());
        Set<String> mutation = new LinkedHashSet<>(graph.managedArtifacts());
        mutation.remove("embabel-agent-decision-typesafe");
        assertThat(validate(graph.withManagedArtifacts(mutation)))
                .contains("managed decision artifacts differ from the allowed set");
    }

    @Test
    void graphContractRejectsAForbiddenLeafFrameworkDependency() throws Exception {
        DecisionGraph graph = readGraph(reactorRoot());
        Map<String, Set<Coordinate>> mutation = copyDependencies(graph.dependencies());
        mutation.put("embabel-agent-decision", Set.of(new Coordinate("org.springframework", "spring-context")));
        assertThat(validate(graph.withDependencies(mutation)))
                .contains("embabel-agent-decision dependencies differ from the allowed set");
    }

    private List<String> validate(DecisionGraph graph) {
        List<String> violations = new ArrayList<>();
        compare(graph.rootDecisionModules(), Set.of("embabel-agent-decisions"), "root decision modules", violations);
        compare(graph.decisionModules(), DECISION_MODULES, "decision aggregate modules", violations);
        compare(graph.autoconfigureDecisionModules(), Set.of("embabel-agent-decision-autoconfigure"),
                "decision autoconfigure modules", violations);
        compare(graph.starterDecisionModules(), Set.of("embabel-agent-starter-decision"),
                "decision starter modules", violations);
        compare(graph.managedArtifacts(), MANAGED_ARTIFACTS, "managed decision artifacts", violations);
        DIRECT_DEPENDENCIES.forEach((artifact, expected) ->
                compare(graph.dependencies().getOrDefault(artifact, Set.of()), expected,
                        artifact + " dependencies", violations));
        return violations;
    }

    private void compare(Set<?> actual, Set<?> expected, String subject, List<String> violations) {
        if (!actual.equals(expected)) violations.add(subject + " differ from the allowed set");
    }

    private DecisionGraph readGraph(Path root) throws Exception {
        Map<String, Path> poms = Map.of(
                "embabel-agent-decision", root.resolve("embabel-agent-decisions/embabel-agent-decision/pom.xml"),
                "embabel-agent-decision-typesafe", root.resolve("embabel-agent-decisions/embabel-agent-decision-typesafe/pom.xml"),
                "embabel-agent-decision-llm", root.resolve("embabel-agent-decisions/embabel-agent-decision-llm/pom.xml"),
                "embabel-agent-decision-autoconfigure", root.resolve("embabel-agent-autoconfigure/embabel-agent-decision-autoconfigure/pom.xml"),
                "embabel-agent-starter-decision", root.resolve("embabel-agent-starters/embabel-agent-starter-decision/pom.xml"));
        Map<String, Set<Coordinate>> dependencies = new LinkedHashMap<>();
        for (var entry : poms.entrySet()) dependencies.put(entry.getKey(), directDependencies(entry.getValue()));
        return new DecisionGraph(
                decisionNamedModules(root.resolve("pom.xml")),
                modules(root.resolve("embabel-agent-decisions/pom.xml")),
                decisionNamedModules(root.resolve("embabel-agent-autoconfigure/pom.xml")),
                decisionNamedModules(root.resolve("embabel-agent-starters/pom.xml")),
                managedDecisionArtifacts(root.resolve("embabel-agent-dependencies/pom.xml")), dependencies);
    }

    private Set<String> modules(Path pom) throws Exception {
        Element modules = directChild(parse(pom), "modules");
        return modules == null ? Set.of() : childTexts(modules, "module");
    }

    private Set<String> decisionNamedModules(Path pom) throws Exception {
        return modules(pom).stream().filter(module -> module.contains("decision"))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<Coordinate> directDependencies(Path pom) throws Exception {
        Element dependencies = directChild(parse(pom), "dependencies");
        if (dependencies == null) return Set.of();
        Set<Coordinate> result = new LinkedHashSet<>();
        for (Element dependency : directChildren(dependencies, "dependency")) {
            if (!"test".equals(childText(dependency, "scope"))) {
                result.add(new Coordinate(childText(dependency, "groupId"), childText(dependency, "artifactId")));
            }
        }
        return result;
    }

    private Set<String> managedDecisionArtifacts(Path pom) throws Exception {
        Element dependencies = directChild(directChild(parse(pom), "dependencyManagement"), "dependencies");
        return directChildren(dependencies, "dependency").stream()
                .map(dependency -> childText(dependency, "artifactId"))
                .filter(artifact -> artifact.startsWith("embabel-agent-decision")
                        || artifact.equals("embabel-agent-starter-decision"))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private Element parse(Path pom) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(pom.toFile()).getDocumentElement();
    }

    private Element directChild(Element parent, String name) {
        return parent == null ? null : directChildren(parent, name).stream().findFirst().orElse(null);
    }

    private List<Element> directChildren(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && name.equals(element.getLocalName())) result.add(element);
        }
        return result;
    }

    private Set<String> childTexts(Element parent, String name) {
        return directChildren(parent, name).stream().map(Element::getTextContent).map(String::trim)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private String childText(Element parent, String name) {
        Element child = directChild(parent, name);
        return child == null ? "" : child.getTextContent().trim();
    }

    private Map<String, Set<Coordinate>> copyDependencies(Map<String, Set<Coordinate>> source) {
        Map<String, Set<Coordinate>> copy = new LinkedHashMap<>();
        source.forEach((artifact, dependencies) -> copy.put(artifact, new LinkedHashSet<>(dependencies)));
        return copy;
    }

    private Path reactorRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null) {
            if (Files.exists(path.resolve("embabel-agent-decisions/pom.xml"))) return path;
            path = path.getParent();
        }
        throw new AssertionError("reactor root not found");
    }

    private record Coordinate(String groupId, String artifactId) {}

    private record DecisionGraph(Set<String> rootDecisionModules, Set<String> decisionModules,
                                 Set<String> autoconfigureDecisionModules, Set<String> starterDecisionModules,
                                 Set<String> managedArtifacts, Map<String, Set<Coordinate>> dependencies) {
        DecisionGraph withDecisionModules(Set<String> value) {
            return new DecisionGraph(rootDecisionModules, value, autoconfigureDecisionModules,
                    starterDecisionModules, managedArtifacts, dependencies);
        }

        DecisionGraph withManagedArtifacts(Set<String> value) {
            return new DecisionGraph(rootDecisionModules, decisionModules, autoconfigureDecisionModules,
                    starterDecisionModules, value, dependencies);
        }

        DecisionGraph withDependencies(Map<String, Set<Coordinate>> value) {
            return new DecisionGraph(rootDecisionModules, decisionModules, autoconfigureDecisionModules,
                    starterDecisionModules, managedArtifacts, value);
        }
    }
}
