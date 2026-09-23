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
import com.embabel.agent.decision.DecisionModelInitialization;
import com.embabel.common.ai.autoconfig.ProviderInitialization;
import kotlin.Metadata;
import kotlin.jvm.JvmClassMappingKt;
import kotlin.reflect.KVisibility;
import org.junit.jupiter.api.Test;
import org.springframework.asm.AnnotationVisitor;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.ai.chat.model.ChatModel;

import java.lang.reflect.Modifier;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionConfigurationArchitectureTest {
    private static final String EXPERIMENTAL_DESCRIPTOR = "Lorg/jetbrains/annotations/ApiStatus$Experimental;";

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
        Method initializer = java.util.Arrays.stream(AgentDecisionAutoConfiguration.class.getDeclaredMethods())
                .filter(method -> method.getReturnType().equals(DecisionModelInitialization.class))
                .findFirst()
                .orElseThrow();
        assertThat(java.util.Arrays.stream(initializer.getGenericParameterTypes())
                .filter(ParameterizedType.class::isInstance)
                .map(ParameterizedType.class::cast)
                .flatMap(type -> java.util.Arrays.stream(type.getActualTypeArguments())))
                .contains(ProviderInitialization.class);
        assertThat(java.util.Arrays.toString(AgentDecisionAutoConfiguration.class.getDeclaredMethods()))
                .doesNotContain("ModelProvider", "ChatModel");

        String receiptSurface = java.util.Arrays.toString(DecisionModelInitialization.class.getDeclaredFields())
                + java.util.Arrays.toString(DecisionModelInitialization.class.getDeclaredConstructors())
                + java.util.Arrays.toString(DecisionModelInitialization.class.getDeclaredMethods());
        assertThat(receiptSurface).doesNotContain("com.embabel.common.ai", "org.springframework");
    }

    @Test
    void factoryDiscoveryWouldRejectAFifthPublicProduct() throws Exception {
        Set<Class<?>> mutated = new LinkedHashSet<>(discoverDecisionFactoryProducts());
        mutated.add(FifthDecisionProduct.class);
        assertThatThrownBy(() -> assertExactlyFourProducts(mutated)).isInstanceOf(AssertionError.class);
    }

    @Test
    void everyCompiledPublicDecisionApiTypeIsExperimental() throws Exception {
        List<String> unannotated = discoverPublicDecisionTypesWithoutExperimentalAnnotation();
        assertThat(unannotated).isEmpty();
    }

    @Test
    void publicApiDiscoveryWouldRejectAnUnannotatedType() throws Exception {
        Path root = Path.of(UnannotatedPublicApi.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path classFile = root.resolve(UnannotatedPublicApi.class.getName().replace('.', '/') + ".class");
        assertThat(readPublicTypeAnnotations(classFile)).isEqualTo(new PublicTypeAnnotations(true, false));
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

    private List<String> discoverPublicDecisionTypesWithoutExperimentalAnnotation() throws Exception {
        Path root = reactorRoot();
        List<Path> artifacts = List.of(
                root.resolve("embabel-agent-decisions/embabel-agent-decision/target/classes"),
                root.resolve("embabel-agent-decisions/embabel-agent-decision-typesafe/target/classes"),
                root.resolve("embabel-agent-decisions/embabel-agent-decision-llm/target/classes"),
                root.resolve("embabel-agent-autoconfigure/embabel-agent-decision-autoconfigure/target/classes"),
                root.resolve("embabel-agent-starters/embabel-agent-starter-decision/target/classes"));
        List<String> unannotated = new ArrayList<>();
        for (Path artifact : artifacts) {
            if (!Files.isDirectory(artifact)) {
                assertThat(artifact.getParent().getParent().resolve("src/main"))
                        .describedAs("an uncompiled runtime artifact cannot contain production sources")
                        .doesNotExist();
                continue;
            }
            try (var files = Files.walk(artifact)) {
                for (Path classFile : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                    PublicTypeAnnotations annotations = readPublicTypeAnnotations(classFile);
                    if (annotations.publicApi() && isSourcePublicApi(artifact, classFile) && !annotations.experimental()) {
                        unannotated.add(artifact.relativize(classFile).toString());
                    }
                }
            }
        }
        return unannotated;
    }

    private PublicTypeAnnotations readPublicTypeAnnotations(Path classFile) throws Exception {
        boolean[] publicType = {false};
        boolean[] synthetic = {false};
        boolean[] experimental = {false};
        new ClassReader(Files.readAllBytes(classFile)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                publicType[0] = (access & Opcodes.ACC_PUBLIC) != 0;
                synthetic[0] = (access & Opcodes.ACC_SYNTHETIC) != 0 || name.endsWith("$DefaultImpls")
                        || name.endsWith("$Companion") || name.contains("$WhenMappings");
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (EXPERIMENTAL_DESCRIPTOR.equals(descriptor)) experimental[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new PublicTypeAnnotations(publicType[0] && !synthetic[0], experimental[0]);
    }

    private boolean isSourcePublicApi(Path artifact, Path classFile) throws ClassNotFoundException {
        String relative = artifact.relativize(classFile).toString();
        if (relative.endsWith("Kt.class")) return false;
        String className = relative.substring(0, relative.length() - ".class".length())
                .replace('/', '.').replace('\\', '.');
        Class<?> type = Class.forName(className, false, getClass().getClassLoader());
        return isSourcePublicApi(type);
    }

    private boolean isSourcePublicApi(Class<?> type) {
        if (!Modifier.isPublic(type.getModifiers())) return false;
        if (type.getAnnotation(Metadata.class) != null
                && JvmClassMappingKt.getKotlinClass(type).getVisibility() != KVisibility.PUBLIC) return false;
        return type.getEnclosingClass() == null || isSourcePublicApi(type.getEnclosingClass());
    }

    private Path reactorRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null) {
            if (Files.exists(path.resolve("embabel-agent-decisions/pom.xml"))) return path;
            path = path.getParent();
        }
        throw new AssertionError("reactor root not found");
    }

    private record PublicTypeAnnotations(boolean publicApi, boolean experimental) {}

    public static final class UnannotatedPublicApi {}

    public static final class FifthDecisionProduct {
        public static DecisionModel create() {
            return com.embabel.agent.decision.NoDecisionModel.create();
        }
    }
}
