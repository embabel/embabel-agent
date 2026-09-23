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

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class DecisionExecutionSupport implements AutoCloseable {
    static final int MAX_WORKERS = 4;
    private static final AtomicLong POOL_IDS = new AtomicLong();

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
        0,
        MAX_WORKERS,
        30,
        TimeUnit.SECONDS,
        new SynchronousQueue<>(),
        daemonThreads(POOL_IDS.incrementAndGet()),
        new ThreadPoolExecutor.AbortPolicy()
    );

    DecisionExecutionSupport() {
    }

    Future<RawDecisionOutcome> submit(Callable<RawDecisionOutcome> work) throws RejectedExecutionException {
        return executor.submit(work);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static ThreadFactory daemonThreads(long poolId) {
        AtomicLong threadIds = new AtomicLong();
        return runnable -> {
            Thread thread = new Thread(runnable, "embabel-decision-" + poolId + "-" + threadIds.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
