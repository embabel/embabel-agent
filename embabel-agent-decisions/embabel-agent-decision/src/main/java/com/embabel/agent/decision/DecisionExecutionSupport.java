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

final class DecisionExecutionSupport {
    static final int MAX_WORKERS = 4;

    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
        MAX_WORKERS,
        MAX_WORKERS,
        0,
        TimeUnit.NANOSECONDS,
        new SynchronousQueue<>(),
        daemonThreads(),
        new ThreadPoolExecutor.AbortPolicy()
    );

    private DecisionExecutionSupport() {
    }

    static Future<RawDecisionOutcome> submit(Callable<RawDecisionOutcome> work) throws RejectedExecutionException {
        return EXECUTOR.submit(work);
    }

    private static ThreadFactory daemonThreads() {
        return runnable -> {
            Thread thread = new Thread(runnable, "embabel-decision");
            thread.setDaemon(true);
            return thread;
        };
    }
}
