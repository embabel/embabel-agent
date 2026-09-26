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
package com.embabel.agent.decision.api

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DecisionExecutionContextTest {

    @Test
    fun `carrier is independent of absent noop and custom instrumentation on real workers`() {
        val local = ThreadLocal<String>()
        val seen = ConcurrentLinkedQueue<String?>()
        val restored = ConcurrentLinkedQueue<String>()
        val carrier = threadLocalCarrier(local, restored)
        val model = DecisionModel(DecisionProvider {
            seen += local.get()
            success()
        })
        assertThat(model.installExecutionContext(carrier)).isTrue()
        val custom = DecisionInstrumentation { DecisionInstrumentation.noop().start(it) }

        local.set("caller")
        try {
            listOf(
                model,
                model.withInstrumentation(DecisionInstrumentation.noop()),
                model.withInstrumentation(custom),
            ).forEach { configured ->
                assertThat(configured.ask(yesNoRequest())).isInstanceOf(DecisionOutcome.Success::class.java)
            }
            assertThat(local.get()).isEqualTo("caller")
        } finally {
            local.remove()
        }

        assertThat(seen).containsExactly("caller", "caller", "caller")
        assertThat(restored).containsExactly("absent", "absent", "absent")
    }

    @Test
    fun `installation is one-time and clones copy selection into independent holders`() {
        val first = CountingContext()
        val second = CountingContext()
        val original = DecisionModel(DecisionProvider { success() })
        val beforeInstall = original.named("before", "custom")

        assertThat(original.installExecutionContext(first)).isTrue()
        assertThat(original.installExecutionContext(second)).isFalse()
        assertThat(beforeInstall.installExecutionContext(second)).isTrue()

        val named = original.named("after", "custom")
        val defaulted = original.withDefaults(Duration.ofSeconds(1), DecisionRecordPolicy.none())
        val instrumented = original.withInstrumentation(DecisionInstrumentation.noop())
        listOf(named, defaulted, instrumented).forEach { clone ->
            assertThat(clone.installExecutionContext(second)).isFalse()
            assertThat(clone.ask(yesNoRequest())).isInstanceOf(DecisionOutcome.Success::class.java)
        }
        assertThat(original.ask(yesNoRequest())).isInstanceOf(DecisionOutcome.Success::class.java)
        assertThat(beforeInstall.ask(yesNoRequest())).isInstanceOf(DecisionOutcome.Success::class.java)

        assertThat(first.wrapCount.get()).isEqualTo(4)
        assertThat(second.wrapCount.get()).isEqualTo(1)
    }

    @Test
    fun `construction failures fail closed before provider invocation`() {
        val invoked = AtomicInteger()
        val model = DecisionModel(DecisionProvider {
            invoked.incrementAndGet()
            success()
        })
        assertThat(model.installExecutionContext(failingConstructionAfterCalling())).isTrue()

        val result = model.ask(yesNoRequest()) as DecisionOutcome.Failure

        assertThat(result.failure).isEqualTo(CallFailure.Unavailable)
        assertThat(invoked.get()).isZero()
    }

    @Test
    fun `construction interruption cancels on caller and fatal error propagates before provider`() {
        val interruptedInvocations = AtomicInteger()
        val interrupted = DecisionModel(DecisionProvider {
            interruptedInvocations.incrementAndGet()
            success()
        })
        assertThat(interrupted.installExecutionContext(failingConstruction(InterruptedException("sentinel")))).isTrue()

        val cancelled = askOnFreshThread(interrupted)

        assertThat((cancelled.first as DecisionOutcome.Failure).failure).isEqualTo(CallFailure.Cancelled)
        assertThat(cancelled.second).isTrue()
        assertThat(interruptedInvocations.get()).isZero()

        val fatal = AssertionError("sentinel-fatal")
        val fatalInvocations = AtomicInteger()
        val fatalModel = DecisionModel(DecisionProvider {
            fatalInvocations.incrementAndGet()
            success()
        })
        assertThat(fatalModel.installExecutionContext(failingConstruction(fatal))).isTrue()
        assertThatThrownBy { fatalModel.ask(yesNoRequest()) }.isSameAs(fatal)
        assertThat(fatalInvocations.get()).isZero()
    }

    @Test
    fun `returned carrier failures map on worker and caller-bound paths without running provider`() {
        val workerInvocations = AtomicInteger()
        val worker = DecisionModel(DecisionProvider {
            workerInvocations.incrementAndGet()
            success()
        })
        assertThat(worker.installExecutionContext(failingCall(IOException("sentinel-worker")))).isTrue()
        val unavailable = worker.ask(yesNoRequest()) as DecisionOutcome.Failure
        assertThat(unavailable.failure).isEqualTo(CallFailure.Unavailable)
        assertThat(workerInvocations.get()).isZero()

        val stub = StubDecisionModel.create(listOf(StubStep.immediate(success())))
        val failingStub = stub.named("failing-stub", "stub")
        assertThat(failingStub.installExecutionContext(failingCall(IOException("sentinel-caller")))).isTrue()
        val callerUnavailable = failingStub.ask(yesNoRequest()) as DecisionOutcome.Failure
        assertThat(callerUnavailable.failure).isEqualTo(CallFailure.Unavailable)
        assertThat(stub.ask(yesNoRequest())).isInstanceOf(DecisionOutcome.Success::class.java)
    }

    @Test
    fun `returned carrier interruption preserves worker and caller cancellation ownership`() {
        val workerInvocations = AtomicInteger()
        val worker = DecisionModel(DecisionProvider {
            workerInvocations.incrementAndGet()
            success()
        })
        assertThat(worker.installExecutionContext(failingCall(InterruptedException("sentinel-worker")))).isTrue()
        val workerResult = worker.ask(yesNoRequest()) as DecisionOutcome.Failure
        assertThat(workerResult.failure).isEqualTo(CallFailure.Cancelled)
        assertThat(workerInvocations.get()).isZero()
        assertThat(Thread.currentThread().isInterrupted).isFalse()

        val stub = StubDecisionModel.create(listOf(StubStep.immediate(success())))
        val failingStub = stub.named("interrupting-stub", "stub")
        assertThat(failingStub.installExecutionContext(failingCall(InterruptedException("sentinel-caller")))).isTrue()
        val callerResult = askOnFreshThread(failingStub)
        assertThat((callerResult.first as DecisionOutcome.Failure).failure).isEqualTo(CallFailure.Cancelled)
        assertThat(callerResult.second).isTrue()
        assertThat(stub.ask(yesNoRequest())).isInstanceOf(DecisionOutcome.Success::class.java)
    }

    @Test
    fun `carrier cannot repeat provider and cannot replace genuine fatal failure`() {
        val invoked = AtomicInteger()
        val repeating = DecisionModel(DecisionProvider {
            invoked.incrementAndGet()
            success()
        })
        assertThat(repeating.installExecutionContext(object : DecisionExecutionContext {
            override fun <T> wrap(work: Callable<T>): Callable<T> = Callable {
                work.call()
                work.call()
            }
        })).isTrue()
        assertThat(repeating.ask(yesNoRequest())).isInstanceOf(DecisionOutcome.Success::class.java)
        assertThat(invoked.get()).isEqualTo(1)

        val fatal = AssertionError("sentinel-work")
        val fatalInvocations = AtomicInteger()
        val fatalModel = DecisionModel(DecisionProvider {
            fatalInvocations.incrementAndGet()
            throw fatal
        })
        assertThat(fatalModel.installExecutionContext(object : DecisionExecutionContext {
            override fun <T> wrap(work: Callable<T>): Callable<T> = Callable {
                try {
                    work.call()
                } catch (_: Throwable) {
                    throw IOException("masked")
                }
            }
        })).isTrue()
        assertThatThrownBy { fatalModel.ask(yesNoRequest()) }.isSameAs(fatal)
        assertThat(fatalInvocations.get()).isEqualTo(1)
    }

    @Test
    fun `fatal carrier failure outranks ordinary provider failure on worker and caller-bound paths`() {
        val workerCarrierFatal = AssertionError("sentinel-worker-carrier")
        val workerInvocations = AtomicInteger()
        val worker = DecisionModel(DecisionProvider {
            workerInvocations.incrementAndGet()
            throw IOException("sentinel-worker-provider")
        })
        assertThat(worker.installExecutionContext(fatalAfterWork(workerCarrierFatal))).isTrue()

        assertThatThrownBy { worker.ask(yesNoRequest()) }.isSameAs(workerCarrierFatal)
        assertThat(workerInvocations.get()).isEqualTo(1)

        val callerCarrierFatal = AssertionError("sentinel-caller-carrier")
        val callerInvocations = AtomicInteger()
        val callerBound = DecisionModel(object : CallerBoundDecisionProvider {
            override fun invoke(request: PreparedDecisionRequest): RawDecisionOutcome {
                callerInvocations.incrementAndGet()
                throw IOException("sentinel-caller-provider")
            }
        })
        assertThat(callerBound.installExecutionContext(fatalAfterWork(callerCarrierFatal))).isTrue()

        assertThatThrownBy { callerBound.ask(yesNoRequest()) }.isSameAs(callerCarrierFatal)
        assertThat(callerInvocations.get()).isEqualTo(1)
    }

    @Test
    fun `fatal provider failure remains authoritative over distinct fatal carrier failure`() {
        val providerFatal = AssertionError("sentinel-provider-fatal")
        val carrierFatal = AssertionError("sentinel-carrier-fatal")
        val invocations = AtomicInteger()
        val model = DecisionModel(DecisionProvider {
            invocations.incrementAndGet()
            throw providerFatal
        })
        assertThat(model.installExecutionContext(fatalAfterWork(carrierFatal))).isTrue()

        assertThatThrownBy { model.ask(yesNoRequest()) }.isSameAs(providerFatal)
        assertThat(invocations.get()).isEqualTo(1)
    }

    @Test
    fun `fatal carrier after provider interruption restores only the executing thread`() {
        val workerFatal = AssertionError("sentinel-worker-carrier")
        val worker = DecisionModel(interruptingProvider())
        assertThat(worker.installExecutionContext(fatalAfterWork(workerFatal))).isTrue()

        assertThatThrownBy { worker.ask(yesNoRequest()) }.isSameAs(workerFatal)
        assertThat(workerFatal.suppressed).hasSize(1)
        assertThat(workerFatal.suppressed.single()).isInstanceOf(InterruptedException::class.java)
        assertThat(Thread.currentThread().isInterrupted).isFalse()

        val callerFatal = AssertionError("sentinel-caller-carrier")
        val callerBound = DecisionModel(object : CallerBoundDecisionProvider {
            override fun invoke(request: PreparedDecisionRequest): RawDecisionOutcome = interruptingProvider().invoke(request)
        })
        assertThat(callerBound.installExecutionContext(fatalAfterWork(callerFatal))).isTrue()

        val caller = askCatchingFatalOnFreshThread(callerBound)
        assertThat(caller.first).isSameAs(callerFatal)
        assertThat(callerFatal.suppressed).hasSize(1)
        assertThat(callerFatal.suppressed.single()).isInstanceOf(InterruptedException::class.java)
        assertThat(caller.second).isTrue()
    }

    @Test
    fun `eager carrier cannot enter instrumentation before establishment`() {
        val providerInvocations = AtomicInteger()
        val instrumentationInvocations = AtomicInteger()
        val instrumentation = DecisionInstrumentation {
            object : DecisionObservation {
                override fun <T> wrap(work: Callable<T>): Callable<T> = Callable {
                    instrumentationInvocations.incrementAndGet()
                    work.call()
                }

                override fun event(event: DecisionTelemetryEvent) = Unit
                override fun complete(completion: DecisionCompletion) = Unit
                override fun close() = Unit
            }
        }
        val model = DecisionModel(DecisionProvider {
            providerInvocations.incrementAndGet()
            success()
        }).withInstrumentation(instrumentation)
        assertThat(model.installExecutionContext(object : DecisionExecutionContext {
            override fun <T> wrap(work: Callable<T>): Callable<T> {
                try {
                    work.call()
                } catch (_: IllegalStateException) {
                    // Invalid eager execution cannot enter instrumentation before establishment.
                }
                return work
            }
        })).isTrue()

        assertThat(model.ask(yesNoRequest())).isInstanceOf(DecisionOutcome.Success::class.java)
        assertThat(providerInvocations.get()).isEqualTo(1)
        assertThat(instrumentationInvocations.get()).isEqualTo(1)
    }

    private class CountingContext : DecisionExecutionContext {
        val wrapCount = AtomicInteger()

        override fun <T> wrap(work: Callable<T>): Callable<T> {
            wrapCount.incrementAndGet()
            return work
        }
    }

    private fun threadLocalCarrier(
        local: ThreadLocal<String>,
        restored: ConcurrentLinkedQueue<String>,
    ) = object : DecisionExecutionContext {
        override fun <T> wrap(work: Callable<T>): Callable<T> {
            val captured = local.get()
            return Callable {
                val previous = local.get()
                local.set(captured)
                try {
                    work.call()
                } finally {
                    if (previous == null) local.remove() else local.set(previous)
                    restored += local.get() ?: "absent"
                }
            }
        }
    }

    private fun failingConstruction(failure: Throwable) = object : DecisionExecutionContext {
        override fun <T> wrap(work: Callable<T>): Callable<T> = throw failure
    }

    private fun failingConstructionAfterCalling() = object : DecisionExecutionContext {
        override fun <T> wrap(work: Callable<T>): Callable<T> {
            work.call()
            throw IOException("sentinel-context")
        }
    }

    private fun failingCall(failure: Exception) = object : DecisionExecutionContext {
        override fun <T> wrap(work: Callable<T>): Callable<T> = Callable { throw failure }
    }

    private fun fatalAfterWork(fatal: Error) = object : DecisionExecutionContext {
        override fun <T> wrap(work: Callable<T>): Callable<T> = Callable {
            try {
                work.call()
            } finally {
                throw fatal
            }
        }
    }

    private fun interruptingProvider() = DecisionProvider {
        Thread.currentThread().interrupt()
        check(Thread.interrupted())
        throw InterruptedException("sentinel-provider")
    }

    private fun askCatchingFatalOnFreshThread(model: DecisionModel): Pair<Throwable?, Boolean> {
        val failure = AtomicReference<Throwable?>()
        val interrupted = AtomicBoolean()
        val caller = Thread {
            try {
                model.ask(yesNoRequest())
            } catch (thrown: Throwable) {
                failure.set(thrown)
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted)
            }
        }
        caller.start()
        caller.join(1_000)
        assertThat(caller.isAlive).isFalse()
        return failure.get() to interrupted.get()
    }

    private fun askOnFreshThread(model: DecisionModel): Pair<DecisionOutcome, Boolean> {
        val outcome = AtomicReference<DecisionOutcome>()
        val interrupted = AtomicBoolean()
        val caller = Thread {
            outcome.set(model.ask(yesNoRequest()))
            interrupted.set(Thread.currentThread().isInterrupted)
        }
        caller.start()
        caller.join(1_000)
        assertThat(caller.isAlive).isFalse()
        return outcome.get() to interrupted.get()
    }

    private fun yesNoRequest(): DecisionRequest = DecisionRequest.builder().also {
        it.yesNo("yes", "yes?")
    }.build()

    private fun success(): RawDecisionOutcome = RawDecisionOutcome.success(
        listOf(RawAnswer.yesNo("yes", 1.0, "true")),
        DecisionProvenance.builder("sentinel", EvidenceKind.DISTRIBUTION).build(),
    )
}
