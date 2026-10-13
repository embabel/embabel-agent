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
package com.embabel.common.ai.model

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.ClassificationService
import com.embabel.common.ai.decision.DecisionService
import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.model.ServiceSelectionException.Reason
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory

class DecisionServiceRegistryTest {

    private class FakeDecision(override val name: String, override val provider: String = "TypeSafe") : DecisionService {
        override fun classify(request: ClassificationRequest): ClassificationResult = error("not called")
        override fun assess(request: PropositionRequest): PropositionResult = error("not called")
    }

    private class FakeClassifier(override val name: String, override val provider: String = "acme") :
        ClassificationService {
        override fun classify(request: ClassificationRequest): ClassificationResult = error("not called")
    }

    private val jev = FakeDecision("jev-1")
    private val jevFast = FakeDecision("jev-fast-1")
    private val prompted = FakeDecision("gpt-x", "OpenAI")
    private val classifier = FakeClassifier("clf-1")

    private fun selectionError(block: () -> Unit): ServiceSelectionException = assertThrows { block() }

    private fun buildError(block: () -> Unit): IllegalArgumentException = assertThrows { block() }

    @Nested
    inner class Resolution {

        private val registry = DecisionServiceRegistry.builder()
            .register("jev", jev)
            .register("jev-fast", jevFast)
            .register("clf", classifier)
            .decisionDefault("jev")
            .classificationDefault("clf")
            .decisionRole("support-triage", "jev-fast")
            .classificationRole("routing", "jev")
            .build()

        @Test
        fun `named role and default resolve for the decision family`() {
            assertSame(jevFast, registry.decisions().named("jev-fast"))
            assertSame(jevFast, registry.decisions().byRole("support-triage"))
            assertSame(jev, registry.decisions().defaultService())
        }

        @Test
        fun `named role and default resolve for the classification family`() {
            assertSame(classifier, registry.classifications().named("clf"))
            assertSame(jev, registry.classifications().byRole("routing"))
            assertSame(classifier, registry.classifications().defaultService())
        }

        @Test
        fun `a decision service satisfies classification and keeps its decision family`() {
            val service = registry.classifications().named("jev")
            assertSame(jev, service)
            assertEquals(ModelType.DECISION, service.type)
        }

        @Test
        fun `a classification-only service asked for as a decision is WRONG_CAPABILITY`() {
            val e = selectionError { registry.decisions().named("clf") }
            assertEquals(Reason.WRONG_CAPABILITY, e.reason)
            assertTrue(e.message!!.contains("'clf'"), e.message)
            assertTrue(e.message!!.contains(FakeClassifier::class.java.name), e.message)
            assertTrue(e.message!!.contains("classifications()"), e.message)
            assertTrue(e.message!!.contains("[jev, jev-fast]"), e.message)
        }

        @Test
        fun `role names are scoped to their family`() {
            assertEquals(Reason.UNKNOWN_ROLE, selectionError { registry.decisions().byRole("routing") }.reason)
            assertEquals(
                Reason.UNKNOWN_ROLE,
                selectionError { registry.classifications().byRole("support-triage") }.reason,
            )
        }

        @Test
        fun `unknown name is UNKNOWN_NAME with eligible names roles default and remedy`() {
            val e = selectionError { registry.decisions().named("jevv") }
            assertEquals(Reason.UNKNOWN_NAME, e.reason)
            val m = e.message!!
            assertTrue(m.contains("'jevv'"), m)
            assertTrue(m.contains("[jev, jev-fast]"), m)
            assertFalse(m.contains("clf,") || m.contains(", clf"), m)
            assertTrue(m.contains("support-triage=jev-fast"), m)
            assertTrue(m.contains("default: 'jev'"), m)
            assertTrue(m.contains("register("), m)
        }

        @Test
        fun `unknown role message names the role property and builder method`() {
            val e = selectionError { registry.decisions().byRole("billing") }
            assertEquals(Reason.UNKNOWN_ROLE, e.reason)
            val m = e.message!!
            assertTrue(m.contains("'billing'"), m)
            assertTrue(m.contains("embabel.models.decision.roles.billing"), m)
            assertTrue(m.contains("decisionRole(\"billing\""), m)
            assertTrue(m.contains("[jev, jev-fast]"), m)
            assertTrue(m.contains("support-triage=jev-fast"), m)
            assertTrue(m.contains("default: 'jev'"), m)
        }

        @Test
        fun `classification unknown role names the classification property and builder method`() {
            val m = selectionError { registry.classifications().byRole("billing") }.message!!
            assertTrue(m.contains("embabel.models.classification.roles.billing"), m)
            assertTrue(m.contains("classificationRole(\"billing\""), m)
            assertTrue(m.contains("[jev, jev-fast, clf]"), m)
        }

        @Test
        fun `using returns its argument`() {
            val adHoc = FakeDecision("per-user")
            assertSame(adHoc, registry.decisions().using(adHoc))
            assertSame(classifier, registry.classifications().using(classifier))
        }

        @Test
        fun `registration names keep registration order and listServices lists each service once`() {
            assertEquals(listOf("jev", "jev-fast", "clf"), registry.registrationNames())
            val listed = registry.listServices()
            assertEquals(listOf("jev-1", "jev-fast-1", "clf-1"), listed.map { it.name })
            assertEquals(listOf(ModelType.DECISION, ModelType.DECISION, ModelType.CLASSIFICATION), listed.map { it.type })
            assertTrue(listed.none { it is ClassificationService })
            assertThrows<UnsupportedOperationException> {
                @Suppress("UNCHECKED_CAST")
                (registry.registrationNames() as MutableList<String>).add("x")
            }
        }
    }

    @Nested
    inner class ProviderModels {

        private inner class ChatModels(
            private val served: List<String>,
            override val provider: String = "OpenAI",
        ) : DecisionModelProvider {
            val created = mutableListOf<String>()

            override fun modelNames(): List<String> = served

            override fun decisionServiceNamed(name: String): DecisionService? =
                if (name in served) FakeDecision(name, provider).also { created += name } else null
        }

        @Test
        fun `a name no service is registered under resolves through a provider once`() {
            val provider = ChatModels(listOf("gpt-x"))
            val registry = DecisionServiceRegistry.builder().register("jev", jev).provider(provider).build()
            val first = registry.decisions().named("gpt-x")
            assertEquals("gpt-x", first.name)
            assertEquals("OpenAI", first.provider)
            assertSame(first, registry.classifications().named("gpt-x"))
            assertEquals(listOf("gpt-x"), provider.created)
        }

        @Test
        fun `a registered service wins over a provider model with the same name`() {
            val provider = ChatModels(listOf("jev"))
            val registry = DecisionServiceRegistry.builder().register("jev", jev).provider(provider).build()
            assertSame(jev, registry.decisions().named("jev"))
            assertTrue(provider.created.isEmpty())
        }

        @Test
        fun `defaults and roles can name a provider model`() {
            // tag::provider-registry[]
            val registry = DecisionServiceRegistry.builder()
                .register("jev", jev)
                .provider(ChatModels(listOf("gpt-x")))
                .decisionDefault("gpt-x")
                .classificationRole("routing", "gpt-x")
                .build()
            // end::provider-registry[]
            assertEquals("gpt-x", registry.decisions().defaultService().name)
            assertSame(registry.decisions().defaultService(), registry.classifications().byRole("routing"))
        }

        @Test
        fun `a default naming a model no provider serves fails the build`() {
            val e = buildError {
                DecisionServiceRegistry.builder()
                    .register("jev", jev)
                    .provider(ChatModels(listOf("gpt-x")))
                    .decisionDefault("gpt-y")
                    .build()
            }
            assertTrue(e.message!!.contains("'gpt-y'"), e.message)
            assertTrue(e.message!!.contains("gpt-x"), e.message)
        }

        @Test
        fun `an unknown name lists the provider models`() {
            val registry = DecisionServiceRegistry.builder()
                .register("jev", jev)
                .provider(ChatModels(listOf("gpt-x")))
                .build()
            val e = selectionError { registry.decisions().named("gpt-y") }
            assertEquals(Reason.UNKNOWN_NAME, e.reason)
            assertTrue(e.message!!.contains("gpt-x"), e.message)
        }

        @Test
        fun `concurrent first lookups build a provider model once`() {
            val started = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val built = java.util.concurrent.atomic.AtomicInteger()
            val slow = object : DecisionModelProvider {
                override val provider = "slow"
                override fun modelNames() = listOf("gpt-x")
                override fun decisionServiceNamed(name: String): DecisionService {
                    built.incrementAndGet()
                    started.countDown()
                    release.await()
                    return FakeDecision(name, provider)
                }
            }
            val registry = DecisionServiceRegistry.builder().provider(slow).build()
            val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val first = pool.submit<DecisionService> { registry.decisions().named("gpt-x") }
                started.await()
                val second = pool.submit<DecisionService> { registry.decisions().named("gpt-x") }
                Thread.sleep(50)
                release.countDown()
                assertSame(first.get(), second.get())
                assertEquals(1, built.get())
            } finally {
                pool.shutdownNow()
            }
        }

        @Test
        fun `a provider that cannot list its models leaves selection errors intact`() {
            val broken = object : DecisionModelProvider {
                override val provider = "broken"
                override fun modelNames(): List<String> = error("discovery failed")
                override fun decisionServiceNamed(name: String): DecisionService? = null
            }
            val registry = DecisionServiceRegistry.builder().register("clf", classifier).provider(broken).build()
            assertEquals(Reason.NO_DEFAULT, selectionError { registry.decisions().defaultService() }.reason)
            assertEquals(Reason.UNKNOWN_ROLE, selectionError { registry.decisions().byRole("missing") }.reason)
            assertEquals(Reason.WRONG_CAPABILITY, selectionError { registry.decisions().named("clf") }.reason)
        }

        @Test
        fun `a provider added to the builder after build does not reach the built registry`() {
            val builder = DecisionServiceRegistry.builder().register("jev", jev)
            val registry = builder.build()
            builder.provider(ChatModels(listOf("gpt-late")))
            val e = selectionError { registry.decisions().named("gpt-late") }
            assertEquals(Reason.UNKNOWN_NAME, e.reason)
            assertFalse(e.message!!.contains("gpt-late provider"), e.message)
            assertFalse(e.message!!.contains("[gpt-late]"), e.message)
        }

        @Test
        fun `provider models never become the default implicitly`() {
            val registry = DecisionServiceRegistry.builder().provider(ChatModels(listOf("gpt-x"))).build()
            assertEquals(Reason.NO_DEFAULT, selectionError { registry.decisions().defaultService() }.reason)
            assertEquals(emptyList<String>(), registry.registrationNames())
        }
    }

    @Nested
    inner class DefaultRule {

        @Test
        fun `explicit default wins over a candidate`() {
            val registry = DecisionServiceRegistry.builder()
                .register("jev", jev).register("gpt", prompted)
                .defaultCandidate("jev").decisionDefault("gpt")
                .build()
            assertSame(prompted, registry.decisions().defaultService())
        }

        @Test
        fun `a single candidate is the default of both families`() {
            val registry = DecisionServiceRegistry.builder()
                .register("gpt", prompted).register("jev-latest", jev)
                .defaultCandidate("jev-latest")
                .build()
            assertSame(jev, registry.decisions().defaultService())
            assertSame(jev, registry.classifications().defaultService())
        }

        @Test
        fun `a candidate not eligible for the decision family is skipped there`() {
            val registry = DecisionServiceRegistry.builder()
                .register("clf", classifier).register("jev", jev)
                .defaultCandidate("clf")
                .build()
            assertSame(jev, registry.decisions().defaultService())
            assertSame(classifier, registry.classifications().defaultService())
        }

        @Test
        fun `two eligible candidates are AMBIGUOUS_DEFAULT naming the candidates and remedy`() {
            val registry = DecisionServiceRegistry.builder()
                .register("jev", jev).register("jev-fast", jevFast)
                .defaultCandidate("jev").defaultCandidate("jev-fast")
                .build()
            val e = selectionError { registry.decisions().defaultService() }
            assertEquals(Reason.AMBIGUOUS_DEFAULT, e.reason)
            val m = e.message!!
            assertTrue(m.contains("candidates considered: [jev, jev-fast]"), m)
            assertTrue(m.contains("embabel.models.decision.default"), m)
            assertTrue(m.contains("decisionDefault("), m)
        }

        @Test
        fun `a unique eligible service is the default`() {
            val registry = DecisionServiceRegistry.builder().register("jev", jev).build()
            assertSame(jev, registry.decisions().defaultService())
            assertSame(jev, registry.classifications().defaultService())
        }

        @Test
        fun `zero eligible services is NO_DEFAULT`() {
            val registry = DecisionServiceRegistry.builder().register("clf", classifier).build()
            val e = selectionError { registry.decisions().defaultService() }
            assertEquals(Reason.NO_DEFAULT, e.reason)
            val m = e.message!!
            assertTrue(m.contains("none set"), m)
            assertTrue(m.contains("Decision services: []"), m)
            assertTrue(m.contains("embabel.models.decision.default"), m)
            assertTrue(m.contains("decisionDefault("), m)
        }

        @Test
        fun `a classifier plus a decision service without default is ambiguous only for classifications`() {
            val registry = DecisionServiceRegistry.builder()
                .register("clf", classifier).register("jev", jev)
                .build()
            assertSame(jev, registry.decisions().defaultService())
            val e = selectionError { registry.classifications().defaultService() }
            assertEquals(Reason.AMBIGUOUS_DEFAULT, e.reason)
            val m = e.message!!
            assertTrue(m.contains("[clf, jev]"), m)
            assertTrue(m.contains("embabel.models.classification.default"), m)
            assertTrue(m.contains("classificationDefault("), m)
        }
    }

    @Nested
    inner class BuildErrors {

        @Test
        fun `duplicate registration name`() {
            val m = buildError {
                DecisionServiceRegistry.builder().register("jev", jev).register("jev", jevFast).build()
            }.message!!
            assertTrue(m.contains("'jev'"), m)
            assertTrue(m.contains("more than once"), m)
        }

        @Test
        fun `same instance under two names`() {
            val m = buildError {
                DecisionServiceRegistry.builder().register("jev", jev).register("jev-2", jev).build()
            }.message!!
            assertTrue(m.contains("'jev'") && m.contains("'jev-2'"), m)
        }

        @Test
        fun `dangling decision role`() {
            val m = buildError {
                DecisionServiceRegistry.builder().register("jev", jev).decisionRole("support-triage", "jevv").build()
            }.message!!
            assertTrue(m.contains("decision role 'support-triage' names unknown service 'jevv'"), m)
            assertTrue(m.contains("embabel.models.decision.roles.support-triage"), m)
        }

        @Test
        fun `decision role naming a classifier`() {
            val m = buildError {
                DecisionServiceRegistry.builder().register("clf", classifier).decisionRole("triage", "clf").build()
            }.message!!
            assertTrue(m.contains("decision role 'triage'"), m)
            assertTrue(m.contains("classificationRole"), m)
        }

        @Test
        fun `decision default naming a classifier and dangling classification default`() {
            assertTrue(buildError {
                DecisionServiceRegistry.builder().register("clf", classifier).decisionDefault("clf").build()
            }.message!!.contains("decision default"))
            assertTrue(buildError {
                DecisionServiceRegistry.builder().register("clf", classifier).classificationDefault("nope").build()
            }.message!!.contains("classification default names unknown service 'nope'"))
        }

        @Test
        fun `unknown candidate`() {
            val m = buildError {
                DecisionServiceRegistry.builder().register("jev", jev).defaultCandidate("jev-latest").build()
            }.message!!
            assertTrue(m.contains("default candidate 'jev-latest' names unknown service"), m)
        }

        @Test
        fun `blank names and roles`() {
            buildError { DecisionServiceRegistry.builder().register(" ", jev).build() }
            buildError { DecisionServiceRegistry.builder().register("jev", jev).decisionRole("", "jev").build() }
            buildError { DecisionServiceRegistry.builder().register("jev", jev).decisionDefault(" ").build() }
        }

        @Test
        fun `a role bound twice to different services`() {
            val m = buildError {
                DecisionServiceRegistry.builder().register("jev", jev).register("jev-fast", jevFast)
                    .decisionRole("triage", "jev").decisionRole("triage", "jev-fast").build()
            }.message!!
            assertTrue(m.contains("decision role 'triage'"), m)
        }
    }

    @Nested
    inner class EmptyAndObservation {

        @Test
        fun `empty registry has no default and using returns the argument`() {
            val empty = DecisionServiceRegistry.empty()
            assertEquals(Reason.NO_DEFAULT, selectionError { empty.decisions().defaultService() }.reason)
            assertEquals(Reason.NO_DEFAULT, selectionError { empty.classifications().defaultService() }.reason)
            assertEquals(Reason.UNKNOWN_NAME, selectionError { empty.decisions().named("jev") }.reason)
            assertSame(jev, empty.decisions().using(jev))
            assertTrue(empty.registrationNames().isEmpty())
            assertTrue(empty.listServices().isEmpty())
            assertSame(ObservationRegistry.NOOP, empty.observationRegistry)
        }

        @Test
        fun `builder keeps the observation registry it was given`() {
            val observations = ObservationRegistry.create()
            val registry = DecisionServiceRegistry.builder().observationRegistry(observations).build()
            assertSame(observations, registry.observationRegistry)
        }

        @Test
        fun `default candidate has value equality`() {
            val candidate = DecisionServiceRegistry.DefaultCandidate("jev-latest")
            assertEquals(DecisionServiceRegistry.DefaultCandidate("jev-latest"), candidate)
            assertEquals(DecisionServiceRegistry.DefaultCandidate("jev-latest").hashCode(), candidate.hashCode())
            assertNotEquals(DecisionServiceRegistry.DefaultCandidate("other"), candidate)
            assertTrue(candidate.toString().contains("jev-latest"))
        }
    }

    @Nested
    inner class Summary {

        private fun captured(block: () -> Unit): List<ILoggingEvent> {
            val logger = LoggerFactory.getLogger(DecisionServiceRegistry::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val previous = logger.level
            logger.addAppender(appender)
            logger.level = Level.DEBUG
            try {
                block()
            } finally {
                logger.detachAppender(appender)
                logger.level = previous
            }
            return appender.list.toList()
        }

        @Test
        fun `build logs one INFO summary with names defaults roles and candidates`() {
            val events = captured {
                DecisionServiceRegistry.builder()
                    .register("jev-latest", jev)
                    .register("gpt", prompted)
                    .register("clf", classifier)
                    .defaultCandidate("jev-latest")
                    .decisionRole("support-triage", "gpt")
                    .classificationRole("routing", "clf")
                    .build()
            }
            assertEquals(1, events.size)
            val event = events.single()
            assertEquals(Level.INFO, event.level)
            val m = event.formattedMessage
            listOf(
                "jev-latest", "jev-1", "TypeSafe", "gpt-x", "OpenAI", "clf-1", "acme",
                "DECISION", "CLASSIFICATION", "support-triage=gpt", "routing=clf",
                "candidates: [jev-latest]",
                "decision default: 'jev-latest' (default candidate)",
            ).forEach { assertTrue(m.contains(it), "missing '$it' in: $m") }
        }

        @Test
        fun `empty logs nothing`() {
            assertTrue(captured { DecisionServiceRegistry.empty() }.isEmpty())
        }
    }
}
