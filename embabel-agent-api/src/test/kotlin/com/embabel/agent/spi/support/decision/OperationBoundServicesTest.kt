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
package com.embabel.agent.spi.support.decision

import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.ClassificationSpec
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.ChoiceQuestionSpec
import com.embabel.common.ai.decision.DecisionCapabilities
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.QuestionKind
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.decision.RatingQuestionSpec
import com.embabel.common.ai.decision.RatingResult
import com.embabel.common.ai.decision.spi.DelegatingDecisionService
import com.embabel.common.ai.decision.spi.NativeQuestionSetExecution
import com.embabel.common.ai.decision.spi.PropositionAssessment
import com.embabel.common.ai.decision.spi.RatingAssessment
import com.embabel.common.ai.decision.support.StubDecisionService
import com.embabel.common.ai.model.ClassificationService
import com.embabel.common.ai.model.DecisionService
import com.embabel.common.ai.model.DecisionServiceRegistry
import com.embabel.common.ai.model.ModelType
import com.embabel.common.ai.model.ServiceSelectionException
import com.embabel.common.ai.model.observation.ObservedClassificationService
import com.embabel.common.ai.model.observation.ObservedDecisionService
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.EnumSet
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OperationBoundServicesTest {

    private val provenance = ModelProvenance("probe-model", "probe")

    private val handler = RecordingHandler()

    private val observations: ObservationRegistry = ObservationRegistry.create().also {
        it.observationConfig().observationHandler(handler)
    }

    private val executor = Executors.newSingleThreadExecutor()

    private val urgent = Questions.named("urgent").proposition("Is it urgent?").build()

    private val team: ChoiceQuestionSpec = Questions.named("team")
        .choice("Which team?")
        .option("billing", "Payments")
        .option("support", "Help")
        .build()

    private val anger: RatingQuestionSpec = Questions.named("anger")
        .rating("How angry?")
        .level("calm")
        .level("angry")
        .build()

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun registryOf(vararg services: Pair<String, ClassificationService>): DecisionServiceRegistry {
        val builder = DecisionServiceRegistry.builder().observationRegistry(observations)
        services.forEach { (name, service) -> builder.register(name, service) }
        return builder.build()
    }

    /** Runs [block] with a started observation named [name] in scope and returns the observation. */
    private fun <R> inObservation(name: String, block: (Observation) -> R): R {
        val observation = Observation.start(name, observations)
        try {
            return observation.openScope().use { block(observation) }
        } finally {
            observation.stop()
        }
    }

    private fun <R> onOtherThread(block: () -> R): R = executor.submit<R> { block() }.get(5, TimeUnit.SECONDS)

    @Test
    fun `a call on the selecting thread runs under the open parent and adds no observation`() {
        val probe = Probe(observations)
        val registry = registryOf("probe" to probe)
        inObservation("action") { action ->
            val bound = OperationBoundServices.decisions(registry).named("probe")
            bound.assess(PropositionRequest("text", "Is it urgent?"))
            assertSame(action, probe.seen.single())
            assertSame(action, observations.currentObservation)
        }
        assertEquals(listOf("action"), handler.started)
    }

    @Test
    fun `a call on another thread runs under the captured parent and restores that thread's scope`() {
        val probe = Probe(observations)
        val registry = registryOf("probe" to probe)
        val (action, bound) = inObservation("action") { action ->
            action to OperationBoundServices.decisions(registry).named("probe")
        }
        val (seenInside, currentAfter, other) = onOtherThread {
            inObservation("other") { other ->
                bound.assess(PropositionRequest("text", "Is it urgent?"))
                Triple(probe.seen.single(), observations.currentObservation, other)
            }
        }
        assertSame(action, seenInside)
        assertSame(other, currentAfter)
        assertEquals(listOf("action", "other"), handler.started)
    }

    @Test
    fun `the other thread's scope is restored after a typed failure, an exception and an interruption`() {
        val probe = Probe(observations)
        val registry = registryOf("probe" to probe)
        val (action, bound) = inObservation("action") { action ->
            action to OperationBoundServices.decisions(registry).named("probe")
        }
        val failures = listOf<Throwable?>(null, IllegalStateException("boom"), InterruptedException("stop"))
        failures.forEach { failure ->
            probe.failure = failure
            probe.seen.clear()
            val result = onOtherThread {
                inObservation("other") { other ->
                    val thrown = runCatching {
                        bound.classify("text", ClassificationSpec.of(team))
                    }
                    assertSame(other, observations.currentObservation)
                    Thread.interrupted()
                    thrown
                }
            }
            assertSame(action, probe.seen.single())
            if (failure == null) {
                assertEquals(ClassificationResult.Failure(FailureReason.UNAVAILABLE), result.getOrThrow())
            } else {
                assertSame(failure, result.exceptionOrNull())
            }
        }
    }

    @Test
    fun `with no observation open at selection the call runs on the caller's scope`() {
        val probe = Probe(observations)
        val bound = OperationBoundServices.decisions(registryOf("probe" to probe)).named("probe")
        val other = onOtherThread {
            inObservation("other") { other ->
                bound.assess(PropositionRequest("text", "Is it urgent?"))
                other
            }
        }
        assertSame(other, probe.seen.single())
    }

    @Test
    fun `the captured parent comes from the registry's observation registry`() {
        val probe = Probe(observations)
        val marker = Observation.start("marker", observations)
        val selectingThread = Thread.currentThread()
        // Reports the marker as current on the selecting thread only, so the binding must open its scope elsewhere.
        val reporting = object : ObservationRegistry by observations {
            override fun getCurrentObservation(): Observation? =
                if (Thread.currentThread() === selectingThread) marker else observations.currentObservation
        }
        val registry = DecisionServiceRegistry.builder()
            .observationRegistry(reporting)
            .register("probe", probe)
            .build()
        val bound = inObservation("action") { OperationBoundServices.decisions(registry).named("probe") }
        val seen = onOtherThread {
            bound.assess(PropositionRequest("text", "Is it urgent?"))
            probe.seen.single()
        }
        marker.stop()
        assertSame(marker, seen)
    }

    @Test
    fun `capabilities and hooks are forwarded for legacy, classifying and native delegates`() {
        val legacy = Probe(observations)
        val choosing = ChoosingProbe(observations)
        val stub = StubDecisionService.builder("native-stub")
            .proposition("urgent", PropositionResult.Answered(true, provenance))
            .choice("team", ClassificationResult.Selected("support", provenance))
            .rating("anger", RatingResult.Answered(provenance, selectedLevelId = "angry"))
            .build()
        val selector = OperationBoundServices.decisions(
            registryOf("legacy" to legacy, "choosing" to choosing, "native" to stub),
        )

        val boundLegacy = selector.named("legacy")
        assertEquals(
            DecisionCapabilities.of(EnumSet.of(QuestionKind.PROPOSITION, QuestionKind.CHOICE)),
            boundLegacy.capabilities(),
        )
        assertSame(legacy, (boundLegacy as DelegatingDecisionService).hookSource)

        val boundChoosing = selector.named("choosing")
        assertEquals(choosing.capabilities(), boundChoosing.capabilities())
        assertTrue(QuestionKind.CHOICE in boundChoosing.capabilities().questionKinds)
        assertEquals(
            ClassificationResult.Selected("support", provenance),
            boundChoosing.classify("text", ClassificationSpec.of(team)),
        )
        assertEquals(1, choosing.classifyCalls)

        val boundNative = selector.named("native")
        assertEquals(stub.capabilities(), boundNative.capabilities())
        val request = DecisionRequest.of("text", urgent, team, anger)
        assertEquals(request.spec.definitionId, (boundNative as NativeQuestionSetExecution).askNative(request).definitionId)
        assertEquals(
            ClassificationResult.Selected("support", provenance),
            boundNative.classify("text", ClassificationSpec.of(team)),
        )
        assertEquals(
            RatingResult.Answered(provenance, selectedLevelId = "angry"),
            (boundNative as RatingAssessment).rate("text", anger),
        )
        assertEquals(
            PropositionResult.Answered(true, provenance),
            (boundNative as PropositionAssessment).assess("text", urgent),
        )
        assertEquals(null, boundNative.ask(request).requestFailure)
        assertEquals(listOf("askNative", "classify", "rate", "assess", "askNative"), stub.calls())
    }

    @Test
    fun `a using delegate that claims a kind without its hook fails with no provider call`() {
        val liar = LyingProbe(observations)
        val bound = OperationBoundServices.decisions(DecisionServiceRegistry.empty()).using(liar)
        assertThrows(IllegalStateException::class.java) { bound.ask(DecisionRequest.of("text", urgent, anger)) }
        assertTrue(liar.seen.isEmpty())
    }

    @Test
    fun `an observed decorator over a binding inspects the hooks of the bound service`() {
        val legacy = Probe(observations)
        val second = Questions.named("second").proposition("Is there more?").build()
        val bound = OperationBoundServices.decisions(registryOf("legacy" to legacy)).named("legacy")
        val observed = ObservedDecisionService(bound, observations)

        val response = observed.ask(DecisionRequest.of("text", urgent, second))

        assertEquals(null, response.requestFailure)
        assertEquals(2, legacy.seen.size)
    }

    @Test
    fun `an observed decorator over a using binding inspects the hooks of the innermost service`() {
        val legacy = Probe(observations)
        val bound = OperationBoundServices.decisions(DecisionServiceRegistry.empty()).using(legacy)
        val observed = ObservedDecisionService(bound, observations)

        assertSame(legacy, observed.hookSource)
        assertEquals(null, observed.ask(DecisionRequest.of("text", urgent)).requestFailure)
        assertEquals(1, legacy.seen.size)
    }

    @Test
    fun `a hook call on a binding whose delegate lacks the hook fails naming the hook and the remedy`() {
        val legacy = Probe(observations)
        val bound = OperationBoundServices.decisions(registryOf("legacy" to legacy)).named("legacy")
        val rate = assertThrows(IllegalStateException::class.java) {
            (bound as RatingAssessment).rate("text", anger)
        }
        assertTrue(rate.message!!.contains("'probe'"), rate.message)
        assertTrue(rate.message!!.contains("does not implement RatingAssessment"), rate.message)
        assertTrue(rate.message!!.contains("remove RATING"), rate.message)
        assertFalse(rate.message!!.contains("How angry"), rate.message)
        assertThrows(IllegalStateException::class.java) {
            (bound as NativeQuestionSetExecution).askNative(DecisionRequest.of("text", urgent))
        }
        val assess = assertThrows(IllegalStateException::class.java) {
            (bound as PropositionAssessment).assess("text", urgent)
        }
        assertTrue(assess.message!!.contains("does not implement PropositionAssessment"), assess.message)
        assertTrue(legacy.seen.isEmpty())
    }

    @Test
    fun `using does not register the service`() {
        val registry = registryOf("probe" to Probe(observations))
        val extra = Probe(observations, "extra")
        val bound = OperationBoundServices.decisions(registry).using(extra)
        assertEquals("extra", bound.name)
        assertEquals(listOf("probe"), registry.registrationNames())
        assertThrows(ServiceSelectionException::class.java) {
            OperationBoundServices.decisions(registry).named("extra")
        }
    }

    @Test
    fun `selectors resolve names, roles and defaults through the registry`() {
        val probe = Probe(observations)
        val registry = DecisionServiceRegistry.builder()
            .observationRegistry(observations)
            .register("probe", probe)
            .decisionRole("triage", "probe")
            .build()
        val selector = OperationBoundServices.decisions(registry)
        listOf(selector.named("probe"), selector.byRole("triage"), selector.defaultService()).forEach {
            assertSame(probe, (it as OperationBoundDecisionService).delegate)
        }
        val missing = assertThrows(ServiceSelectionException::class.java) { selector.byRole("absent") }
        assertEquals(ServiceSelectionException.Reason.UNKNOWN_ROLE, missing.reason)
    }

    @Test
    fun `metadata and type equal the delegate's`() {
        val probe = Probe(observations)
        val classifier = Classifier()
        val registry = registryOf("jev" to probe, "classifier" to classifier)
        val bound = OperationBoundServices.decisions(registry).named("jev")
        assertEquals(probe.metadata(), bound.metadata())
        assertEquals(probe.type, bound.type)
        assertEquals(probe.name, bound.name)
        assertEquals(probe.provider, bound.provider)
        assertEquals(probe.infoString(true, 2), bound.infoString(true, 2))

        val boundClassifier = OperationBoundServices.classifications(registry).named("classifier")
        assertEquals(classifier.metadata(), boundClassifier.metadata())
        assertEquals(ModelType.CLASSIFICATION, boundClassifier.type)
    }

    @Test
    fun `a decision service selected as a classification service reports DECISION`() {
        val registry = registryOf("jev" to Probe(observations))
        val bound = OperationBoundServices.classifications(registry).named("jev")
        assertEquals(ModelType.DECISION, bound.type)
        assertEquals(ModelType.DECISION, bound.metadata().type)
        assertTrue(bound is DecisionService)
    }

    @Test
    fun `binding a binding again binds the original service once`() {
        val probe = Probe(observations)
        val registry = registryOf("probe" to probe)
        val first = OperationBoundServices.decisions(registry).named("probe")
        assertSame(probe, (first as OperationBoundDecisionService).delegate)
        val second = OperationBoundServices.decisions(registry).using(first)
        val observed = (second as OperationBoundDecisionService).delegate
        assertInstanceOf(ObservedDecisionService::class.java, observed)
        val third = OperationBoundServices.decisions(registry).using(second)
        assertSame(observed, (third as OperationBoundDecisionService).delegate)
        val asClassification = OperationBoundServices.classifications(registry).using(second)
        assertSame(observed, (asClassification as OperationBoundDecisionService).delegate)

        val classifier = Classifier()
        val boundClassifier = OperationBoundServices.classifications(registry).using(classifier)
        val observedClassifier = (boundClassifier as OperationBoundClassificationService).delegate
        assertInstanceOf(ObservedClassificationService::class.java, observedClassifier)
        val rebound = OperationBoundServices.classifications(registry).using(boundClassifier)
        assertSame(observedClassifier, (rebound as OperationBoundClassificationService).delegate)
    }

    @Test
    fun `a hand-built service passed to using records one ask observation under the operation`() {
        val probe = Probe(observations)
        val registry = registryOf()
        inObservation("action") {
            val bound = OperationBoundServices.decisions(registry).using(probe)
            bound.ask("text", DecisionSpec.of(urgent))
        }
        assertEquals(listOf("action", "embabel.ai.ask", "embabel.ai.decision"), handler.started)
        assertEquals("action", handler.parents["embabel.ai.ask"])
        assertEquals("embabel.ai.ask", handler.parents["embabel.ai.decision"])
    }

    @Test
    fun `classify with a spec on a using binding records one classification observation under the operation`() {
        val spec = ClassificationSpec.of(team)
        inObservation("action") {
            OperationBoundServices.classifications(registryOf()).using(Classifier()).classify("text", spec)
            OperationBoundServices.decisions(registryOf()).using(Probe(observations)).classify("text", spec)
        }
        assertEquals(
            listOf("action", "embabel.ai.classification", "embabel.ai.classification"),
            handler.started,
        )
        assertEquals("action", handler.parents["embabel.ai.classification"])
    }

    @Test
    fun `an observed service passed to using is not wrapped again`() {
        val observed = ObservedDecisionService(Probe(observations), observations)
        inObservation("action") {
            val bound = OperationBoundServices.decisions(registryOf()).using(observed)
            assertSame(observed, (bound as OperationBoundDecisionService).delegate)
            bound.ask("text", DecisionSpec.of(urgent))
        }
        assertEquals(1, handler.started.count { it == "embabel.ai.ask" })
    }

    /** Records the name of every observation started on the registry. */
    private class RecordingHandler : ObservationHandler<Observation.Context> {
        val started = CopyOnWriteArrayList<String>()
        val parents = java.util.concurrent.ConcurrentHashMap<String, String>()

        override fun onStart(context: Observation.Context) {
            started += context.name
            context.parentObservation?.contextView?.name?.let { parents[context.name] = it }
        }

        override fun supportsContext(context: Observation.Context): Boolean = true
    }

    /** A legacy decision service that records the current observation on each call. */
    private open class Probe(
        private val observations: ObservationRegistry,
        override val name: String = "probe",
    ) : DecisionService {
        val seen = CopyOnWriteArrayList<Observation?>()

        @Volatile
        var failure: Throwable? = null

        override val provider: String = "probe"

        protected fun record() {
            seen += listOf(observations.currentObservation)
            failure?.let { throw it }
        }

        override fun classify(request: ClassificationRequest): ClassificationResult {
            record()
            return ClassificationResult.Failure(FailureReason.UNAVAILABLE)
        }

        override fun assess(request: PropositionRequest): PropositionResult {
            record()
            return PropositionResult.Answered(true, ModelProvenance("probe-model", "probe"))
        }
    }

    /** A probe that answers choice questions through classify, selecting `support`. */
    private class ChoosingProbe(observations: ObservationRegistry) : Probe(observations, "choosing") {
        var classifyCalls = 0

        override fun classify(request: ClassificationRequest): ClassificationResult {
            record()
            classifyCalls++
            return ClassificationResult.Selected("support", ModelProvenance("probe-model", "probe"))
        }
    }

    /** A probe whose capabilities claim rating questions without the rating hook. */
    private class LyingProbe(observations: ObservationRegistry) : Probe(observations, "liar") {
        override fun capabilities(): DecisionCapabilities =
            DecisionCapabilities.of(EnumSet.of(QuestionKind.PROPOSITION, QuestionKind.RATING))
    }

    /** A classification-only service. */
    private class Classifier : ClassificationService {
        override val name: String = "classifier"
        override val provider: String = "probe"

        override fun classify(request: ClassificationRequest): ClassificationResult =
            ClassificationResult.Failure(FailureReason.UNAVAILABLE)
    }
}
