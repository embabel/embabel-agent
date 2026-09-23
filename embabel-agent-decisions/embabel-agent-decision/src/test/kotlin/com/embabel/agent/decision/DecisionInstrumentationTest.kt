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
package com.embabel.agent.decision

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DecisionInstrumentationTest {

    @Test
    fun `reports one completion with key outcome counts and bounded provider family`() {
        val instrumentation = RecordingInstrumentation()
        val request = DecisionRequest.builder()
        request.yesNo("present", "present?")
        request.yesNo("missing", "missing?")
        val model = DecisionModel(DecisionProvider {
            it.event(DecisionTelemetryEvent.PROVIDER_ATTEMPT)
            RawDecisionOutcome.success(
                listOf(RawAnswer.yesNo("present", 1.0, "true")),
                DecisionProvenance.builder("sentinel-provider", EvidenceKind.DISTRIBUTION).build(),
            )
        }).named("sentinel-name", "prompted").withInstrumentation(instrumentation)

        val result = model.ask(request.build())

        assertThat(result).isInstanceOf(DecisionOutcome.Success::class.java)
        assertThat(instrumentation.contexts).hasSize(1)
        val context = instrumentation.contexts.single()
        assertThat(context.providerFamily).isEqualTo(DecisionProviderFamily.PROMPTED)
        assertThat(context.questionCount).isEqualTo(2)
        assertThat(instrumentation.events).containsExactly(DecisionTelemetryEvent.PROVIDER_ATTEMPT)
        assertThat(instrumentation.completions).hasSize(1)
        val completion = instrumentation.completions.single()
        assertThat(completion.status).isEqualTo(DecisionCompletionStatus.SUCCESS)
        assertThat(completion.safeCode).isNull()
        assertThat(completion.keySuccessCount).isEqualTo(1)
        assertThat(completion.keyFailureCount).isEqualTo(1)
        assertThat(completion.elapsedNanos).isNotNegative()
        assertThat(instrumentation.closeCount.get()).isEqualTo(1)
    }

    @Test
    fun `reports every call failure once`() {
        CallFailure.entries.forEach { failure ->
            val instrumentation = RecordingInstrumentation()
            val model = DecisionModel(DecisionProvider {
                RawDecisionOutcome.failure(failure, DecisionSafeCode.INVALID)
            }).withInstrumentation(instrumentation)

            val result = model.ask(yesNoRequest()) as DecisionOutcome.Failure

            assertThat(instrumentation.completions).hasSize(1)
            val completion = instrumentation.completions.single()
            assertThat(completion.status).isEqualTo(DecisionCompletionStatus.FAILURE)
            assertThat(completion.safeCode).isEqualTo(result.safeCode)
            assertThat(completion.keySuccessCount).isZero()
            assertThat(completion.keyFailureCount).isZero()
            assertThat(instrumentation.closeCount.get()).isEqualTo(1)
        }
    }

    @Test
    fun `reports preparation rejection once without exposing the request`() {
        val instrumentation = RecordingInstrumentation()
        val foreignRequest = object : DecisionRequest {}

        val result = DecisionModel(DecisionProvider {
            throw AssertionError("provider must not run")
        }).withInstrumentation(instrumentation).ask(foreignRequest) as DecisionOutcome.Failure

        assertThat(result.failure).isEqualTo(CallFailure.RejectedRequest)
        assertThat(instrumentation.contexts.single().questionCount).isZero()
        assertThat(instrumentation.completions).hasSize(1)
        assertThat(instrumentation.completions.single().safeCode).isEqualTo(DecisionSafeCode.REJECTED_REQUEST)
        assertThat(instrumentation.closeCount.get()).isEqualTo(1)
    }

    @Test
    fun `observer runtime failures cannot change work or skip later lifecycle hooks`() {
        val invoked = AtomicInteger()
        val completed = AtomicInteger()
        val closed = AtomicInteger()
        val instrumentation = DecisionInstrumentation {
            object : DecisionObservation {
                override fun <T> wrap(work: Callable<T>): Callable<T> = Callable {
                    val result = work.call()
                    throw IllegalStateException("sentinel-after-work")
                    @Suppress("UNREACHABLE_CODE")
                    result
                }

                override fun event(event: DecisionTelemetryEvent) {
                    throw IllegalStateException("sentinel-event")
                }

                override fun complete(completion: DecisionCompletion) {
                    completed.incrementAndGet()
                    throw IllegalStateException("sentinel-complete")
                }

                override fun close() {
                    closed.incrementAndGet()
                    throw IllegalStateException("sentinel-close")
                }
            }
        }
        val model = DecisionModel(DecisionProvider {
            invoked.incrementAndGet()
            it.event(DecisionTelemetryEvent.PROVIDER_ATTEMPT)
            success()
        }).withInstrumentation(instrumentation)

        assertThat(model.ask(yesNoRequest())).isInstanceOf(DecisionOutcome.Success::class.java)
        assertThat(invoked.get()).isEqualTo(1)
        assertThat(completed.get()).isEqualTo(1)
        assertThat(closed.get()).isEqualTo(1)
    }

    @Test
    fun `checked hook failures cannot change outcome or skip close`() {
        CheckedHook.entries.forEach { failingHook ->
            val invoked = AtomicInteger()
            val closed = AtomicInteger()
            val instrumentation = checkedFailureInstrumentation(failingHook, closed)
            val model = DecisionModel(DecisionProvider { prepared ->
                invoked.incrementAndGet()
                prepared.event(DecisionTelemetryEvent.PROVIDER_ATTEMPT)
                success()
            }).withInstrumentation(instrumentation)

            assertThat(model.ask(yesNoRequest())).describedAs(failingHook.name)
                .isInstanceOf(DecisionOutcome.Success::class.java)
            assertThat(invoked.get()).describedAs(failingHook.name).isEqualTo(1)
            if (failingHook != CheckedHook.START) {
                assertThat(closed.get()).describedAs(failingHook.name).isEqualTo(1)
            }

            if (failingHook == CheckedHook.WRAP) {
                val callerBoundClosed = AtomicInteger()
                val stub = StubDecisionModel.create(listOf(StubStep.immediate(success())))
                assertThat(
                    stub.withInstrumentation(checkedFailureInstrumentation(failingHook, callerBoundClosed))
                        .ask(yesNoRequest()),
                ).isInstanceOf(DecisionOutcome.Success::class.java)
                assertThat(callerBoundClosed.get()).isEqualTo(1)
                val exhausted = stub.withInstrumentation(DecisionInstrumentation.noop())
                    .ask(yesNoRequest()) as DecisionOutcome.Failure
                assertThat(exhausted.failure).isEqualTo(CallFailure.Unsupported)
            }
        }
    }

    @Test
    fun `checked wrapper failures and skipped work preserve genuine worker result exactly once`() {
        WrapperBehavior.entries.forEach { behavior ->
            val invoked = AtomicInteger()
            val model = DecisionModel(DecisionProvider {
                invoked.incrementAndGet()
                success()
            }).withInstrumentation(wrapperInstrumentation(behavior))

            assertThat(model.ask(yesNoRequest())).isInstanceOf(DecisionOutcome.Success::class.java)
            assertThat(invoked.get()).describedAs(behavior.name).isEqualTo(1)
        }
    }

    @Test
    fun `checked wrapper failures and skipped work preserve genuine caller bound result exactly once`() {
        WrapperBehavior.entries.forEach { behavior ->
            val model = StubDecisionModel.create(listOf(StubStep.immediate(success())))
                .withInstrumentation(wrapperInstrumentation(behavior))

            assertThat(model.ask(yesNoRequest())).isInstanceOf(DecisionOutcome.Success::class.java)
            val exhausted = model.withInstrumentation(DecisionInstrumentation.noop())
                .ask(yesNoRequest()) as DecisionOutcome.Failure
            assertThat(exhausted.failure).describedAs(behavior.name).isEqualTo(CallFailure.Unsupported)
        }
    }

    @Test
    fun `start and wrap failures fall back without running provider twice`() {
        val invoked = AtomicInteger()
        val startFailure = DecisionInstrumentation {
            throw IllegalStateException("sentinel-start")
        }
        val wrapFailure = DecisionInstrumentation {
            object : DecisionObservation {
                override fun <T> wrap(work: Callable<T>): Callable<T> =
                    throw IllegalStateException("sentinel-wrap")

                override fun event(event: DecisionTelemetryEvent) = Unit
                override fun complete(completion: DecisionCompletion) = Unit
                override fun close() = Unit
            }
        }
        val provider = DecisionProvider {
            invoked.incrementAndGet()
            success()
        }

        assertThat(DecisionModel(provider).withInstrumentation(startFailure).ask(yesNoRequest()))
            .isInstanceOf(DecisionOutcome.Success::class.java)
        assertThat(DecisionModel(provider).withInstrumentation(wrapFailure).ask(yesNoRequest()))
            .isInstanceOf(DecisionOutcome.Success::class.java)
        assertThat(invoked.get()).isEqualTo(2)
    }

    @Test
    fun `default installation preserves identity is one-time and explicit choice wins`() {
        val first = RecordingInstrumentation()
        val second = RecordingInstrumentation()
        val model = DecisionModel(DecisionProvider { success() })

        assertThat(model.installDefaultInstrumentation(first)).isTrue()
        assertThat(model.installDefaultInstrumentation(second)).isFalse()
        assertThat(model.ask(yesNoRequest())).isInstanceOf(DecisionOutcome.Success::class.java)
        assertThat(first.completions).hasSize(1)
        assertThat(second.completions).isEmpty()

        val explicit = model.withInstrumentation(second)
        assertThat(explicit.installDefaultInstrumentation(first)).isFalse()
        assertThat(explicit.ask(yesNoRequest())).isInstanceOf(DecisionOutcome.Success::class.java)
        assertThat(second.completions).hasSize(1)

        val explicitNoop = model.withInstrumentation(DecisionInstrumentation.noop())
        assertThat(explicitNoop.installDefaultInstrumentation(first)).isFalse()
    }

    @Test
    fun `named and defaulted clones retain an independent copy of the instrumentation selection`() {
        val instrumentation = RecordingInstrumentation()
        val model = DecisionModel(DecisionProvider { success() })
        assertThat(model.installDefaultInstrumentation(instrumentation)).isTrue()
        val named = model.named("named", "typesafe")
        val defaulted = model.withDefaults(Duration.ofSeconds(2), DecisionRecordPolicy.none())

        assertThat(named.installDefaultInstrumentation(RecordingInstrumentation())).isFalse()
        assertThat(defaulted.installDefaultInstrumentation(RecordingInstrumentation())).isFalse()
        named.ask(yesNoRequest())
        defaulted.ask(yesNoRequest())

        assertThat(instrumentation.contexts.map { it.providerFamily })
            .containsExactly(DecisionProviderFamily.TYPESAFE, DecisionProviderFamily.CUSTOM)

        val unconfiguredClone = DecisionModel(DecisionProvider { success() }).named("before", "stub")
        val cloneInstrumentation = RecordingInstrumentation()
        assertThat(unconfiguredClone.installDefaultInstrumentation(cloneInstrumentation)).isTrue()
        assertThat(unconfiguredClone.named("after", "none").installDefaultInstrumentation(instrumentation)).isFalse()
    }

    @Test
    fun `concurrent default installation has one winner`() {
        val model = DecisionModel(DecisionProvider { success() })
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val winners = ConcurrentLinkedQueue<DecisionInstrumentation>()
        val candidates = List(8) { RecordingInstrumentation() }
        val threads = candidates.map { candidate ->
            Thread {
                ready.countDown()
                start.await()
                if (model.installDefaultInstrumentation(candidate)) winners += candidate
            }.also(Thread::start)
        }

        assertThat(ready.await(1, TimeUnit.SECONDS)).isTrue()
        start.countDown()
        threads.forEach { it.join(1_000) }
        assertThat(winners).hasSize(1)
        model.ask(yesNoRequest())
        assertThat((winners.single() as RecordingInstrumentation).completions).hasSize(1)
    }

    @Test
    fun `worker interruption maps to cancelled without changing caller interrupt status`() {
        val instrumentation = RecordingInstrumentation()
        val result = DecisionModel(DecisionProvider {
            throw InterruptedException("sentinel-worker")
        }).withInstrumentation(instrumentation).ask(yesNoRequest()) as DecisionOutcome.Failure

        assertThat(result.failure).isEqualTo(CallFailure.Cancelled)
        assertThat(Thread.currentThread().isInterrupted).isFalse()
        assertThat(instrumentation.completions).hasSize(1)
        assertThat(instrumentation.completions.single().safeCode).isEqualTo(DecisionSafeCode.CANCELLED)
    }

    @Test
    fun `pre-work wrapper interruption cancels worker call without invoking provider`() {
        val invoked = AtomicInteger()
        val result = DecisionModel(DecisionProvider {
            invoked.incrementAndGet()
            success()
        }).withInstrumentation(preWorkInterruption()).ask(yesNoRequest()) as DecisionOutcome.Failure

        assertThat(result.failure).isEqualTo(CallFailure.Cancelled)
        assertThat(invoked.get()).isZero()
        assertThat(Thread.currentThread().isInterrupted).isFalse()
    }

    @Test
    fun `pre-work wrapper interruption cancels caller-bound calls without consuming stub step`() {
        val none = askOnFreshThread(NoDecisionModel.create().withInstrumentation(preWorkInterruption()))
        assertThat((none.first as DecisionOutcome.Failure).failure).isEqualTo(CallFailure.Cancelled)
        assertThat(none.second).isTrue()

        val stub = StubDecisionModel.create(listOf(StubStep.immediate(success())))
        val cancelled = askOnFreshThread(stub.withInstrumentation(preWorkInterruption()))
        assertThat((cancelled.first as DecisionOutcome.Failure).failure).isEqualTo(CallFailure.Cancelled)
        assertThat(cancelled.second).isTrue()
        assertThat(stub.withInstrumentation(DecisionInstrumentation.noop()).ask(yesNoRequest()))
            .isInstanceOf(DecisionOutcome.Success::class.java)
        val exhausted = stub.withInstrumentation(DecisionInstrumentation.noop())
            .ask(yesNoRequest()) as DecisionOutcome.Failure
        assertThat(exhausted.failure).isEqualTo(CallFailure.Unsupported)
    }

    @Test
    fun `wrapper construction interruption cancels on caller without invoking provider`() {
        val invoked = AtomicInteger()
        val result = askOnFreshThread(
            DecisionModel(DecisionProvider {
                invoked.incrementAndGet()
                success()
            }).withInstrumentation(wrapperConstructionInterruption()),
        )

        assertThat((result.first as DecisionOutcome.Failure).failure).isEqualTo(CallFailure.Cancelled)
        assertThat(result.second).isTrue()
        assertThat(invoked.get()).isZero()
    }

    @Test
    fun `post-work wrapper interruption preserves caller-bound result flag and one stub step`() {
        val stub = StubDecisionModel.create(listOf(StubStep.immediate(success())))

        val result = askOnFreshThread(stub.withInstrumentation(postWorkInterruption()))

        assertThat(result.first).isInstanceOf(DecisionOutcome.Success::class.java)
        assertThat(result.second).isTrue()
        val exhausted = stub.withInstrumentation(DecisionInstrumentation.noop())
            .ask(yesNoRequest()) as DecisionOutcome.Failure
        assertThat(exhausted.failure).isEqualTo(CallFailure.Unsupported)
    }

    @Test
    fun `post-work wrapper interruption preserves worker result without changing caller flag`() {
        val invoked = AtomicInteger()
        val model = DecisionModel(DecisionProvider {
            invoked.incrementAndGet()
            success()
        }).withInstrumentation(postWorkInterruption())

        assertThat(model.ask(yesNoRequest())).isInstanceOf(DecisionOutcome.Success::class.java)
        assertThat(invoked.get()).isEqualTo(1)
        assertThat(Thread.currentThread().isInterrupted).isFalse()
    }

    @Test
    fun `fatal instrumentation after provider interruption restores only the executing thread`() {
        val workerFatal = AssertionError("sentinel-worker-instrumentation")
        val worker = DecisionModel(interruptingProvider()).withInstrumentation(fatalAfterWorkInstrumentation(workerFatal))

        assertThatThrownBy { worker.ask(yesNoRequest()) }.isSameAs(workerFatal)
        assertThat(workerFatal.suppressed).hasSize(1)
        assertThat(workerFatal.suppressed.single()).isInstanceOf(InterruptedException::class.java)
        assertThat(Thread.currentThread().isInterrupted).isFalse()

        val callerFatal = AssertionError("sentinel-caller-instrumentation")
        val callerBound = DecisionModel(object : CallerBoundDecisionProvider {
            override fun invoke(request: PreparedDecisionRequest): RawDecisionOutcome = interruptingProvider().invoke(request)
        }).withInstrumentation(fatalAfterWorkInstrumentation(callerFatal))

        val caller = askCatchingFatalOnFreshThread(callerBound)
        assertThat(caller.first).isSameAs(callerFatal)
        assertThat(callerFatal.suppressed).hasSize(1)
        assertThat(callerFatal.suppressed.single()).isInstanceOf(InterruptedException::class.java)
        assertThat(caller.second).isTrue()
    }

    @Test
    fun `fatal provider errors propagate after completion and close`() {
        val instrumentation = RecordingInstrumentation()
        val fatal = AssertionError("sentinel-fatal")
        val model = DecisionModel(DecisionProvider { throw fatal }).withInstrumentation(instrumentation)

        assertThatThrownBy { model.ask(yesNoRequest()) }.isSameAs(fatal)
        assertThat(instrumentation.completions).hasSize(1)
        assertThat(instrumentation.completions.single().status).isEqualTo(DecisionCompletionStatus.FAILURE)
        assertThat(instrumentation.completions.single().safeCode).isNull()
        assertThat(instrumentation.closeCount.get()).isEqualTo(1)
    }

    @Test
    fun `fatal completion errors propagate after close is attempted`() {
        val closed = AtomicInteger()
        val fatal = AssertionError("sentinel-completion-fatal")
        val instrumentation = DecisionInstrumentation {
            object : DecisionObservation {
                override fun <T> wrap(work: Callable<T>): Callable<T> = work
                override fun event(event: DecisionTelemetryEvent) = Unit
                override fun complete(completion: DecisionCompletion) = throw fatal
                override fun close() {
                    closed.incrementAndGet()
                }
            }
        }

        assertThatThrownBy {
            DecisionModel(DecisionProvider { success() })
                .withInstrumentation(instrumentation)
                .ask(yesNoRequest())
        }.isSameAs(fatal)
        assertThat(closed.get()).isEqualTo(1)
    }

    @Test
    fun `closed execution is distinct from capacity rejection`() {
        val instrumentation = RecordingInstrumentation()
        val model = DecisionModel(DecisionProvider { success() }).withInstrumentation(instrumentation)
        model.close()

        val result = model.ask(yesNoRequest()) as DecisionOutcome.Failure

        assertThat(result.failure).isEqualTo(CallFailure.Unavailable)
        assertThat(instrumentation.events).containsExactly(DecisionTelemetryEvent.MODEL_CLOSED)
        assertThat(instrumentation.completions).hasSize(1)
    }

    @Test
    fun `saturated execution retains its capacity rejection event`() {
        val instrumentation = RecordingInstrumentation()
        val entered = CountDownLatch(DecisionExecutionSupport.MAX_WORKERS)
        val release = CountDownLatch(1)
        val model = DecisionModel(DecisionProvider {
            entered.countDown()
            release.await()
            success()
        }).withInstrumentation(instrumentation)
        val request = DecisionRequest.builder().also {
            it.timeout(Duration.ofSeconds(2))
            it.yesNo("yes", "yes?")
        }.build()
        val workers = List(DecisionExecutionSupport.MAX_WORKERS) {
            Thread { model.ask(request) }.also(Thread::start)
        }

        try {
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue()
            val result = model.ask(request) as DecisionOutcome.Failure

            assertThat(result.failure).isEqualTo(CallFailure.Unavailable)
            assertThat(instrumentation.events).containsExactly(DecisionTelemetryEvent.CAPACITY_REJECTED)
        } finally {
            release.countDown()
            workers.forEach { it.join(1_000) }
            model.close()
        }
    }

    @Test
    fun `seals provider events before timeout cancellation signals the facade`() {
        val providerEventAttempted = CountDownLatch(1)
        val instrumentation = cancellationRaceInstrumentation(
            DecisionTelemetryEvent.TIMEOUT,
            providerEventAttempted,
        )
        val entered = CountDownLatch(1)
        val request = DecisionRequest.builder().also {
            it.timeout(Duration.ofMillis(40))
            it.yesNo("yes", "yes?")
        }.build()
        val model = DecisionModel(DecisionProvider { prepared ->
            entered.countDown()
            try {
                CountDownLatch(1).await()
                throw AssertionError("provider wait unexpectedly completed")
            } catch (error: InterruptedException) {
                prepared.event(DecisionTelemetryEvent.RETRY)
                providerEventAttempted.countDown()
                throw error
            }
        }).withInstrumentation(instrumentation)

        val result = model.ask(request) as DecisionOutcome.Failure

        assertThat(entered.count).isZero()
        assertThat(result.failure).isEqualTo(CallFailure.DeadlineExceeded)
        assertThat(instrumentation.events).containsExactly(DecisionTelemetryEvent.TIMEOUT)
    }

    @Test
    fun `seals provider events before caller cancellation signals the facade`() {
        val providerEventAttempted = CountDownLatch(1)
        val instrumentation = cancellationRaceInstrumentation(
            DecisionTelemetryEvent.CANCELLATION,
            providerEventAttempted,
        )
        val entered = CountDownLatch(1)
        val outcome = AtomicReference<DecisionOutcome>()
        val callerInterrupted = AtomicBoolean()
        val model = DecisionModel(DecisionProvider { prepared ->
            entered.countDown()
            try {
                CountDownLatch(1).await()
                throw AssertionError("provider wait unexpectedly completed")
            } catch (error: InterruptedException) {
                prepared.event(DecisionTelemetryEvent.RETRY)
                providerEventAttempted.countDown()
                throw error
            }
        }).withInstrumentation(instrumentation)
        val caller = Thread {
            outcome.set(model.ask(yesNoRequest()))
            callerInterrupted.set(Thread.currentThread().isInterrupted)
        }
        caller.start()
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue()

        caller.interrupt()
        caller.join(1_000)

        assertThat(caller.isAlive).isFalse()
        assertThat((outcome.get() as DecisionOutcome.Failure).failure).isEqualTo(CallFailure.Cancelled)
        assertThat(callerInterrupted.get()).isTrue()
        assertThat(instrumentation.events).containsExactly(DecisionTelemetryEvent.CANCELLATION)
    }

    @Test
    fun `drops provider events after terminal completion`() {
        val instrumentation = RecordingInstrumentation()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val emitted = CountDownLatch(1)
        val request = DecisionRequest.builder().also {
            it.timeout(Duration.ofMillis(20))
            it.yesNo("yes", "yes?")
        }.build()
        val model = DecisionModel(DecisionProvider { prepared ->
            entered.countDown()
            while (release.count > 0) try {
                release.await()
            } catch (_: InterruptedException) {
                // Model a transport that cannot cooperate with cancellation.
            }
            prepared.event(DecisionTelemetryEvent.RETRY)
            emitted.countDown()
            success()
        }).withInstrumentation(instrumentation)

        val result = model.ask(request) as DecisionOutcome.Failure
        assertThat(entered.count).isZero()
        assertThat(result.failure).isEqualTo(CallFailure.DeadlineExceeded)
        release.countDown()
        assertThat(emitted.await(1, TimeUnit.SECONDS)).isTrue()
        assertThat(instrumentation.events).containsExactly(DecisionTelemetryEvent.TIMEOUT)
        assertThat(instrumentation.completions).hasSize(1)
    }

    @Test
    fun `telemetry types expose only bounded payload-free values`() {
        val exposedTypes = listOf(
            DecisionInstrumentation::class.java,
            DecisionObservation::class.java,
            DecisionObservationContext::class.java,
            DecisionCompletion::class.java,
        ).flatMap { type -> type.declaredMethods.flatMap { listOf(it.returnType, *it.parameterTypes) } }.toSet()

        assertThat(exposedTypes).doesNotContain(
            DecisionRequest::class.java,
            PreparedDecisionRequest::class.java,
            DecisionOutcome::class.java,
            DecisionRecord::class.java,
            Throwable::class.java,
            String::class.java,
        )
    }

    @Test
    fun `default logs exclude model provider payload and exception sentinels`() {
        val logger = LoggerFactory.getLogger(DecisionModel::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also {
            it.start()
            logger.addAppender(it)
        }
        val request = DecisionRequest.builder()
            .state(mapOf("secret-key" to "sentinel-payload"))
            .correlationId("sentinel-correlation")
            .also { it.yesNo("sentinel-key", "sentinel-question?") }
            .build()
        val model = DecisionModel(DecisionProvider {
            throw IllegalStateException("sentinel-exception")
        }).named("sentinel-name", "sentinel-provider")

        try {
            assertThat(model.ask(request)).isInstanceOf(DecisionOutcome.Failure::class.java)
            val logs = appender.list.joinToString("\n") { it.formattedMessage }
            assertThat(logs)
                .doesNotContain("sentinel-name")
                .doesNotContain("sentinel-provider")
                .doesNotContain("sentinel-payload")
                .doesNotContain("sentinel-correlation")
                .doesNotContain("sentinel-key")
                .doesNotContain("sentinel-question")
                .doesNotContain("sentinel-exception")
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `intentional disabled and unsupported completions log at debug while unavailable warns`() {
        val logger = LoggerFactory.getLogger(DecisionModel::class.java) as Logger
        val previousLevel = logger.level
        val appender = ListAppender<ILoggingEvent>().also {
            it.start()
            logger.addAppender(it)
        }
        logger.level = Level.DEBUG

        try {
            NoDecisionModel.create().ask(yesNoRequest())
            DecisionModel(DecisionProvider {
                RawDecisionOutcome.failure(CallFailure.Unsupported, DecisionSafeCode.UNSUPPORTED)
            }).ask(yesNoRequest())
            DecisionModel(DecisionProvider {
                RawDecisionOutcome.failure(CallFailure.Unavailable, DecisionSafeCode.UNAVAILABLE)
            }).ask(yesNoRequest())

            val completions = appender.list.filter { it.formattedMessage.startsWith("Decision completed") }
            assertThat(completions.map { it.level }).containsExactly(Level.DEBUG, Level.DEBUG, Level.WARN)
        } finally {
            logger.level = previousLevel
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    private open class RecordingInstrumentation : DecisionInstrumentation {
        val contexts = ConcurrentLinkedQueue<DecisionObservationContext>()
        val events = ConcurrentLinkedQueue<DecisionTelemetryEvent>()
        val completions = ConcurrentLinkedQueue<DecisionCompletion>()
        val closeCount = AtomicInteger()

        override fun start(context: DecisionObservationContext): DecisionObservation {
            contexts += context
            return object : DecisionObservation {
                override fun <T> wrap(work: Callable<T>): Callable<T> = work
                override fun event(event: DecisionTelemetryEvent) {
                    onEvent(event)
                    events += event
                }

                override fun complete(completion: DecisionCompletion) {
                    completions += completion
                }

                override fun close() {
                    closeCount.incrementAndGet()
                }
            }
        }

        open fun onEvent(event: DecisionTelemetryEvent) = Unit
    }

    private enum class WrapperBehavior { CHECKED_BEFORE_WORK, CHECKED_AFTER_WORK, SKIP_WORK }

    private enum class CheckedHook { START, WRAP, EVENT, COMPLETE, CLOSE }

    private fun checkedFailureInstrumentation(
        failingHook: CheckedHook,
        closed: AtomicInteger,
    ) = DecisionInstrumentation {
        if (failingHook == CheckedHook.START) throw IOException("sentinel-start")
        object : DecisionObservation {
            override fun <T> wrap(work: Callable<T>): Callable<T> =
                if (failingHook == CheckedHook.WRAP) throw IOException("sentinel-wrap") else work

            override fun event(event: DecisionTelemetryEvent) {
                if (failingHook == CheckedHook.EVENT) throw IOException("sentinel-event")
            }

            override fun complete(completion: DecisionCompletion) {
                if (failingHook == CheckedHook.COMPLETE) throw IOException("sentinel-complete")
            }

            override fun close() {
                closed.incrementAndGet()
                if (failingHook == CheckedHook.CLOSE) throw IOException("sentinel-close")
            }
        }
    }

    private fun preWorkInterruption() = DecisionInstrumentation {
        object : DecisionObservation {
            override fun <T> wrap(work: Callable<T>): Callable<T> = Callable {
                throw InterruptedException("sentinel-wrapper")
            }

            override fun event(event: DecisionTelemetryEvent) = Unit
            override fun complete(completion: DecisionCompletion) = Unit
            override fun close() = Unit
        }
    }

    private fun wrapperConstructionInterruption() = DecisionInstrumentation {
        object : DecisionObservation {
            override fun <T> wrap(work: Callable<T>): Callable<T> =
                throw InterruptedException("sentinel-construction")

            override fun event(event: DecisionTelemetryEvent) = Unit
            override fun complete(completion: DecisionCompletion) = Unit
            override fun close() = Unit
        }
    }

    private fun postWorkInterruption() = DecisionInstrumentation {
        object : DecisionObservation {
            override fun <T> wrap(work: Callable<T>): Callable<T> = Callable {
                work.call()
                Thread.currentThread().interrupt()
                check(Thread.interrupted())
                throw InterruptedException("sentinel-after-work")
            }

            override fun event(event: DecisionTelemetryEvent) = Unit
            override fun complete(completion: DecisionCompletion) = Unit
            override fun close() = Unit
        }
    }

    private fun fatalAfterWorkInstrumentation(fatal: Error) = DecisionInstrumentation {
        object : DecisionObservation {
            override fun <T> wrap(work: Callable<T>): Callable<T> = Callable {
                try {
                    work.call()
                } finally {
                    throw fatal
                }
            }

            override fun event(event: DecisionTelemetryEvent) = Unit
            override fun complete(completion: DecisionCompletion) = Unit
            override fun close() = Unit
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

    private fun wrapperInstrumentation(behavior: WrapperBehavior) = DecisionInstrumentation {
        object : DecisionObservation {
            override fun <T> wrap(work: Callable<T>): Callable<T> = when (behavior) {
                WrapperBehavior.CHECKED_BEFORE_WORK -> Callable {
                    throw IOException("sentinel-before-work")
                }
                WrapperBehavior.CHECKED_AFTER_WORK -> Callable {
                    work.call()
                    throw IOException("sentinel-after-work")
                }
                WrapperBehavior.SKIP_WORK -> Callable {
                    @Suppress("UNCHECKED_CAST")
                    null as T
                }
            }

            override fun event(event: DecisionTelemetryEvent) = Unit
            override fun complete(completion: DecisionCompletion) = Unit
            override fun close() = Unit
        }
    }

    private fun cancellationRaceInstrumentation(
        facadeEvent: DecisionTelemetryEvent,
        providerEventAttempted: CountDownLatch,
    ): RecordingInstrumentation = object : RecordingInstrumentation() {
        override fun onEvent(event: DecisionTelemetryEvent) {
            if (event == facadeEvent) {
                val interrupted = Thread.interrupted()
                try {
                    assertThat(providerEventAttempted.await(1, TimeUnit.SECONDS)).isTrue()
                } finally {
                    if (interrupted) Thread.currentThread().interrupt()
                }
            }
        }
    }

    private fun yesNoRequest(): DecisionRequest = DecisionRequest.builder().also {
        it.yesNo("yes", "yes?")
    }.build()

    private fun success(): RawDecisionOutcome = RawDecisionOutcome.success(
        listOf(RawAnswer.yesNo("yes", 1.0, "true")),
        DecisionProvenance.builder("sentinel-provenance", EvidenceKind.DISTRIBUTION).build(),
    )
}
