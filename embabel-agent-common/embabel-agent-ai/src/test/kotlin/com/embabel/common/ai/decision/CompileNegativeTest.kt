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
package com.embabel.common.ai.decision

import com.embabel.common.ai.decision.spi.DecisionExecution
import com.embabel.common.ai.model.DecisionService
import com.embabel.common.ai.model.observation.ObservedDecisionService
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.tools.Diagnostic
import javax.tools.JavaFileObject
import javax.tools.StandardLocation
import javax.tools.ToolProvider

// What one compile attempt produced: whether it succeeded, and the text of every error it reported.
private data class CompileOutcome(val success: Boolean, val errors: List<String>)

// Compiles one Java source file against this test's own classpath, so it sees the classes this
// module already built. Nothing here is printed; the caller decides what a result means. The
// output goes to [keepIn] when given, and the caller owns that directory. Otherwise it goes to a
// temp directory that is deleted afterwards.
private fun compileJava(source: Path, keepIn: Path? = null): CompileOutcome {
    val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) {
        "No system Java compiler is available. Run this test with a JDK, not a JRE."
    }
    val diagnostics = javax.tools.DiagnosticCollector<JavaFileObject>()
    val outDir = keepIn ?: Files.createTempDirectory("compile-negative-java")
    val success = try {
        compiler.getStandardFileManager(diagnostics, null, null).use { fileManager ->
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, listOf(outDir.toFile()))
            val units = fileManager.getJavaFileObjectsFromFiles(listOf(source.toFile()))
            val options = listOf("-classpath", CompileNegativeTest.classpath)
            compiler.getTask(null, fileManager, diagnostics, options, null, units).call()
        }
    } finally {
        if (keepIn == null) outDir.toFile().deleteRecursively()
    }
    val errors = diagnostics.diagnostics
        .filter { it.kind == Diagnostic.Kind.ERROR }
        .map { it.getMessage(null) }
    return CompileOutcome(success && errors.isEmpty(), errors)
}

// Compiles one Kotlin source file with the embedded K2JVMCompiler, against the same classpath.
// The compiler needs org.jetbrains:annotations on that classpath to resolve the parameter
// annotations already baked into this module's own class files; it is already there because the
// module keeps it as a "provided" dependency, so it lands on this test's own classpath too.
private fun compileKotlin(source: Path): CompileOutcome {
    val outDir = Files.createTempDirectory("compile-negative-kotlin")
    val errors = mutableListOf<String>()
    val collector = object : MessageCollector {
        override fun clear() {
            errors.clear()
        }

        override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
            if (severity.isError) errors += message
        }

        override fun hasErrors(): Boolean = errors.isNotEmpty()
    }
    val compiler = K2JVMCompiler()
    val arguments = compiler.createArguments()
    compiler.parseArguments(
        arrayOf(
            "-classpath", CompileNegativeTest.classpath,
            "-d", outDir.toString(),
            "-no-stdlib",
            "-no-reflect",
            "-jvm-target", "21",
            source.toString(),
        ),
        arguments,
    )
    val exitCode = try {
        compiler.exec(collector, Services.EMPTY, arguments)
    } finally {
        outDir.toFile().deleteRecursively()
    }
    return CompileOutcome(exitCode == ExitCode.OK, errors)
}

private fun assertCompiles(outcome: CompileOutcome) {
    assertTrue(outcome.success) { "Expected this fixture to compile. Errors: ${outcome.errors}" }
}

private fun assertFailsWith(fragment: String, outcome: CompileOutcome) {
    assertFalse(outcome.success) { "Expected this fixture to fail to compile, but it succeeded" }
    assertTrue(outcome.errors.any { it.contains(fragment) }) {
        "Expected an error containing '$fragment'. Actual errors: ${outcome.errors}"
    }
}

/**
 * Compiles every decision spec API fixture on its own, in a temp directory, against this test's
 * own classpath. A positive fixture must compile. A negative fixture must fail with an error that
 * contains its expected fragment, so a change that breaks the check in a different way is caught
 * too.
 *
 * The positive fixtures are asserted first, in [assertPositiveFixturesCompileFirst]. If the
 * classpath or toolchain setup is broken, that assertion fails and this whole class is reported
 * as failed, so a negative fixture never looks like it passed for the wrong reason.
 */
class CompileNegativeTest {

    companion object {
        // Every fixture compiles against exactly the classpath this test itself runs with.
        @JvmStatic
        val classpath: String = System.getProperty("java.class.path")

        private val fixturesRoot: Path by lazy {
            val url = requireNotNull(Thread.currentThread().contextClassLoader.getResource("compile-fixtures")) {
                "compile-fixtures test resources were not found on the classpath"
            }
            Paths.get(url.toURI())
        }

        private fun positive(vararg segments: String): Path = fixturesRoot.resolve(Paths.get("positive", *segments))
        private fun negative(vararg segments: String): Path = fixturesRoot.resolve(Paths.get("negative", *segments))

        @JvmStatic
        @BeforeAll
        fun assertPositiveFixturesCompileFirst() {
            val java = compileJava(positive("java", "Triage.java"))
            check(java.success) {
                "The Java triage fixture must compile before any negative result can be trusted. Errors: ${java.errors}"
            }
            val legacy = compileJava(positive("java", "LegacyJavaDecisionService.java"))
            check(legacy.success) {
                "The Java legacy decision service fixture must compile before any negative result can be trusted. " +
                    "Errors: ${legacy.errors}"
            }
            val kotlin = compileKotlin(positive("kotlin", "DslExample.kt"))
            check(kotlin.success) {
                "The Kotlin DSL fixture must compile before any negative result can be trusted. Errors: ${kotlin.errors}"
            }
        }
    }

    @Test
    fun `the Java triage example compiles`() {
        assertCompiles(compileJava(positive("java", "Triage.java")))
    }

    @Test
    fun `the Kotlin DSL example compiles`() {
        assertCompiles(compileKotlin(positive("kotlin", "DslExample.kt")))
    }

    @Nested
    inner class LegacyJavaImplementor {

        private val newMembers = setOf("ask", "capabilities", "askNative", "choose", "rate")

        private val proposition = Questions.named("urgent").proposition("Is it urgent?").build()

        private val choice = Questions.named("team").choice("Which team?")
            .option("billing", "Payments")
            .option("support", "Help")
            .build()

        @Test
        fun `a base-API Java service declares no new member and works through the interface and the decorator`() {
            val outDir = Files.createTempDirectory("compile-legacy-java")
            try {
                assertCompiles(compileJava(positive("java", "LegacyJavaDecisionService.java"), keepIn = outDir))
                URLClassLoader(arrayOf(outDir.toUri().toURL()), javaClass.classLoader).use { loader ->
                    val type = loader.loadClass("com.embabel.common.ai.decision.fixtures.LegacyJavaDecisionService")
                    val declared = type.declaredMethods.map { it.name }.toSet()
                    assertTrue(declared.intersect(newMembers).isEmpty()) {
                        "The legacy fixture declares ${declared.intersect(newMembers)}"
                    }

                    val fixture = type.getDeclaredConstructor().newInstance() as DecisionService
                    for (service in listOf(fixture, ObservedDecisionService(fixture))) {
                        val response = service.ask("input", DecisionSpec.of(proposition))
                        assertInstanceOf(PropositionResult.Answered::class.java, response.answer(proposition))
                        assertEquals(DecisionExecution.LEGACY_CAPABILITIES, service.capabilities())
                        assertThrows<UnsupportedDecisionException> { service.ask("input", DecisionSpec.of(choice)) }
                    }
                }
            } finally {
                outDir.toFile().deleteRecursively()
            }
        }
    }

    @Nested
    inner class JavaNegatives {

        @Test
        fun `option is not a member of the proposition builder`() {
            assertFailsWith(
                "option(java.lang.String,java.lang.String)",
                compileJava(negative("java", "OptionOnPropositionBuilder.java")),
            )
        }

        @Test
        fun `level is not a member of the choice builder`() {
            assertFailsWith("level(java.lang.String)", compileJava(negative("java", "LevelOnChoiceBuilder.java")))
        }

        @Test
        fun `option is not a member of the rating builder`() {
            assertFailsWith(
                "option(java.lang.String,java.lang.String)",
                compileJava(negative("java", "OptionOnRatingBuilder.java")),
            )
        }

        @Test
        fun `a proposition answer cannot be read as a classification result`() {
            assertFailsWith("PropositionResult", compileJava(negative("java", "AnswerTypeMismatch.java")))
        }

        @Test
        fun `a question has no public constructor`() {
            assertFailsWith(
                "has private access in com.embabel.common.ai.decision.PropositionQuestionSpec",
                compileJava(negative("java", "PrivateConstructor.java")),
            )
        }

        @Test
        fun `question is sealed to its three kinds`() {
            assertFailsWith("permits", compileJava(negative("java", "QuestionImplementation.java")))
        }

        @Test
        fun `a builder's internal factory is hidden from Java`() {
            assertFailsWith("create\$embabel_agent_ai(java.lang.String)", compileJava(negative("java", "MangledFactoryCall.java")))
        }

        @Test
        fun `the response builder's answer overloads are typed by question kind`() {
            assertFailsWith(
                "answer(com.embabel.common.ai.decision.PropositionQuestionSpec," +
                    "com.embabel.common.ai.decision.RatingResult)",
                compileJava(negative("java", "TypedOverloadMismatch.java")),
            )
        }
    }

    @Nested
    inner class KotlinNegatives {

        @Test
        fun `choice cannot be reached from inside a proposition block`() {
            assertFailsWith(
                "cannot be called in this context",
                compileKotlin(negative("kotlin", "ChoiceInsidePropositionBlock.kt")),
            )
        }

        @Test
        fun `option is not in scope inside a proposition block`() {
            assertFailsWith("Unresolved reference 'option'", compileKotlin(negative("kotlin", "OptionInsidePropositionBlock.kt")))
        }
    }
}
