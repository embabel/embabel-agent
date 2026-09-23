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

import com.embabel.agent.decision.DecisionModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionConfigurationArchitectureTest {
    @Test
    void configurationIsNotAnotherModelProductOrLlmRegistration() {
        assertThat(DecisionModel.class.getModifiers()).matches(Modifier::isFinal);
        assertThat(com.embabel.agent.spi.LlmService.class.isAssignableFrom(DecisionModel.class)).isFalse();
        assertThat(ChatModel.class.isAssignableFrom(DecisionModel.class)).isFalse();
        assertThat(AgentDecisionAutoConfiguration.class.getDeclaredMethods())
                .filteredOn(method -> Modifier.isPublic(method.getModifiers()))
                .isEmpty();
    }

    @Test
    void noRuntimeHintsOrReflectiveWireBindingAreIntroduced() {
        String methods = java.util.Arrays.toString(AgentDecisionAutoConfiguration.class.getDeclaredMethods());
        assertThat(methods).doesNotContain("readValue", "treeToValue", "convertValue", "RuntimeHintsRegistrar");
    }

    @Test
    void publicFactoriesRemainExactlyTheFourNamedProductsReturningTheFinalFacade() throws Exception {
        Set<Class<?>> products = discoverDecisionFactoryProducts();
        assertExactlyFourProducts(products);
        assertThat(products).allSatisfy(product -> assertThat(java.util.Arrays.stream(product.getDeclaredMethods())
                        .filter(method -> Modifier.isPublic(method.getModifiers()))
                        .filter(method -> method.getName().equals("create")))
                .isNotEmpty()
                .allSatisfy(method -> assertThat(method.getReturnType()).isEqualTo(DecisionModel.class)));
        assertThat(java.util.Arrays.toString(AgentDecisionAutoConfiguration.class.getDeclaredMethods()))
                .doesNotContain("ProviderInitialization", "ModelProvider", "ChatModel", "LlmService");
    }

    @Test
    void factoryDiscoveryWouldRejectAFifthPublicProduct() throws Exception {
        Set<Class<?>> mutated = new LinkedHashSet<>(discoverDecisionFactoryProducts());
        mutated.add(FifthDecisionProduct.class);
        assertThatThrownBy(() -> assertExactlyFourProducts(mutated)).isInstanceOf(AssertionError.class);
    }

    private void assertExactlyFourProducts(Set<Class<?>> products) {
        assertThat(products).containsExactlyInAnyOrder(
                com.embabel.agent.decision.typesafe.TypeSafeDecisionModel.class,
                com.embabel.agent.decision.llm.PromptedDecisionModel.class,
                com.embabel.agent.decision.NoDecisionModel.class,
                com.embabel.agent.decision.StubDecisionModel.class);
    }

    private Set<Class<?>> discoverDecisionFactoryProducts() throws Exception {
        Set<Class<?>> classes = new LinkedHashSet<>();
        for (Class<?> anchor : Set.of(
                DecisionModel.class,
                com.embabel.agent.decision.typesafe.TypeSafeDecisionModel.class,
                com.embabel.agent.decision.llm.PromptedDecisionModel.class)) {
            scanCompiledArtifact(anchor, classes);
        }
        return classes.stream()
                .filter(type -> Modifier.isPublic(type.getModifiers()))
                .filter(type -> java.util.Arrays.stream(type.getDeclaredMethods()).anyMatch(method ->
                        Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers())
                                && method.getName().equals("create")
                                && method.getReturnType().equals(DecisionModel.class)))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private void scanCompiledArtifact(Class<?> anchor, Set<Class<?>> classes) throws Exception {
        URI origin = anchor.getProtectionDomain().getCodeSource().getLocation().toURI();
        Path path = Path.of(origin);
        if (Files.isDirectory(path)) {
            Path packageRoot = path.resolve("com/embabel/agent/decision");
            try (var files = Files.walk(packageRoot)) {
                for (Path file : files.filter(candidate -> candidate.toString().endsWith(".class")).toList()) {
                    loadClass(path, file, classes);
                }
            }
        } else {
            try (JarFile jar = new JarFile(path.toFile())) {
                for (var entries = jar.entries(); entries.hasMoreElements();) {
                    var entry = entries.nextElement();
                    if (entry.getName().startsWith("com/embabel/agent/decision/") && entry.getName().endsWith(".class")) {
                        loadClass(entry.getName(), classes);
                    }
                }
            }
        }
    }

    private void loadClass(Path root, Path file, Set<Class<?>> classes) throws ClassNotFoundException {
        loadClass(root.relativize(file).toString(), classes);
    }

    private void loadClass(String relativeClassFile, Set<Class<?>> classes) throws ClassNotFoundException {
        if (relativeClassFile.contains("$")) return;
        String className = relativeClassFile.substring(0, relativeClassFile.length() - ".class".length())
                .replace('/', '.').replace('\\', '.');
        classes.add(Class.forName(className, false, getClass().getClassLoader()));
    }

    public static final class FifthDecisionProduct {
        public static DecisionModel create() {
            return com.embabel.agent.decision.NoDecisionModel.create();
        }
    }
}
