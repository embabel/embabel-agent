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
package com.embabel.agent.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Arrays;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

class DecisionModelJavaTest {

    @Test
    void javaConsumerReadsAChoiceWithoutACast() {
        DecisionRequest.Builder request = DecisionRequest.builder();
        ChoiceKey<String> relation = request.choice("relation", "Relation?", List.of(
            DecisionOption.of("same", "IDENTICAL", "identical"),
            DecisionOption.of("other", "OTHER", "other")
        ));
        DecisionModel model = new DecisionModel(prepared -> RawDecisionOutcome.success(List.of(
            RawAnswer.distribution("relation", DecisionKind.CHOICE, List.of(
                RawProbability.of("same", 1.0), RawProbability.of("other", 0.0)
            ), "same")
        ), DecisionProvenance.builder("java-test", EvidenceKind.DISTRIBUTION).build()));

        DecisionOutcome.Success outcome = assertInstanceOf(DecisionOutcome.Success.class, model.ask(request.build()));
        assertEquals("IDENTICAL", outcome.value(relation));
        assertEquals("java-test", outcome.getProvenance().getProvider());
    }

    @Test
    void javaConsumerCanNameAndInspectARegistryFacade() {
        DecisionModel model = NoDecisionModel.create().named("disabled", "none");

        assertEquals("disabled", model.getName());
        assertEquals("none", model.getProvider());
        assertEquals("none", NoDecisionModel.create().getName());
        assertEquals("stub", StubDecisionModel.create(List.of(
            StubStep.immediate(RawDecisionOutcome.failure(CallFailure.Disabled, DecisionSafeCode.DISABLED))
        )).getName());

        try (DecisionModelInitialization initialization = new DecisionModelInitialization(List.of(model))) {
            assertEquals(List.of(model), initialization.getCreatedModels());
        }
    }

    @Test
    void exposesOnlyTheSanctionedFacadeConstructorAndCompilesTypedJavaUsage() {
        assertTrue(Modifier.isFinal(DecisionModel.class.getModifiers()));
        assertEquals(1, DecisionModel.class.getConstructors().length);
        assertEquals(1, DecisionModel.class.getConstructors()[0].getParameterCount());
        assertTrue(DecisionRequest.class.isInterface());
        assertTrue(Modifier.isFinal(DecisionOutcome.Success.class.getModifiers()));
        assertTrue(Modifier.isFinal(KeyOutcome.Success.class.getModifiers()));
        assertTrue(Arrays.stream(DecisionModel.class.getMethods())
            .noneMatch(method -> method.getName().contains("$")));
        assertEquals(0, DecisionModel.class.getFields().length);
        assertEquals(0, Arrays.stream(DecisionContractsKt.class.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .count());

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        String classpath = System.getProperty("java.class.path");
        String positive = """
            import com.embabel.agent.decision.*;
            import java.util.List;
            class PositiveConsumer {
                String use() {
                    DecisionRequest.Builder request = DecisionRequest.builder();
                    ChoiceKey<String> key = request.choice("q", "Question?", List.of(DecisionOption.of("yes", "YES", "yes")));
                    DecisionModel model = new DecisionModel(prepared -> RawDecisionOutcome.failure(CallFailure.Disabled, DecisionSafeCode.DISABLED));
                    DecisionModel named = model.named("java", "custom");
                    String registryIdentity = named.getName() + ":" + named.getProvider();
                    DecisionOutcome result = model.ask(request.build());
                    if (result instanceof DecisionOutcome.Success success) {
                        String value = success.value(key);
                        return value;
                    }
                    return "failed";
                }
            }
            """;
        String bypass = """
            import com.embabel.agent.decision.*;
            import java.time.Duration;
            class FacadeBypass {
                DecisionModel bypass(DecisionProvider provider) {
                    return new DecisionModel(provider, Duration.ofMillis(1), DecisionRecordPolicy.metadata(), () -> 0L);
                }
            }
            """;
        String forge = """
            import com.embabel.agent.decision.*;
            class ForgeDecisionSuccess extends DecisionOutcome {
                ForgeDecisionSuccess() { }
            }
            """;

        assertEquals(0, compile(compiler, classpath, "PositiveConsumer", positive));
        assertTrue(compile(compiler, classpath, "FacadeBypass", bypass) != 0);
        assertTrue(compile(compiler, classpath, "ForgeDecisionSuccess", forge) != 0);
    }

    private static int compile(JavaCompiler compiler, String classpath, String className, String source) {
        try {
            java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("decision-java-compiler");
            java.nio.file.Path file = directory.resolve(className + ".java");
            java.nio.file.Files.writeString(file, source);
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            return compiler.run(null, null, errors, "-Xlint:unchecked", "-Werror", "-classpath", classpath, file.toString());
        } catch (java.io.IOException error) {
            throw new AssertionError(error);
        }
    }
}
