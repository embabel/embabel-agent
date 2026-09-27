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
package com.embabel.common.ai.decision.annotated;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Checks that the decision cache returns one instance per type and lets a type's class loader be
 * collected once the application drops it.
 */
class AnnotatedDecisionsCacheTest {

    @Test
    void repeatedReadsReturnTheSameInstance() {
        AnnotatedDecisions decisions = AnnotatedDecisions.using(JsonMapper.builder().build());

        AnnotatedDecision<UnloadableTriage> first = decisions.of(UnloadableTriage.class);

        assertSame(first, decisions.of(UnloadableTriage.class));
        assertSame(AnnotatedDecisions.defaults().of(UnloadableTriage.class),
            AnnotatedDecisions.defaults().of(UnloadableTriage.class));
        assertNotSame(first, AnnotatedDecisions.defaults().of(UnloadableTriage.class));
    }

    @Test
    void defaultsDoesNotHoldTheClassLoaderOfATypeItRead() throws Exception {
        AnnotatedDecisions decisions = AnnotatedDecisions.defaults();

        WeakReference<ClassLoader> loader = readInThrowawayLoader(decisions);
        // The mapper's own type and deserializer caches also hold the class. They are Jackson's and are
        // cleared here so the check covers the decision cache only.
        decisions.mapper().clearCaches();

        assertCollected(loader);
    }

    @Test
    void aRetainedInstanceDoesNotHoldTheClassLoaderOfATypeItRead() throws Exception {
        AnnotatedDecisions decisions = AnnotatedDecisions.using(JsonMapper.builder().build());

        WeakReference<ClassLoader> loader = readInThrowawayLoader(decisions);
        decisions.mapper().clearCaches();

        assertCollected(loader);
        assertEquals(List.of("urgent"), decisions.of(UnloadableTriage.class).spec().getQuestions().stream()
            .map(question -> question.getName()).toList());
    }

    private static WeakReference<ClassLoader> readInThrowawayLoader(AnnotatedDecisions decisions) throws Exception {
        ClassLoader loader = new SingleClassLoader(UnloadableTriage.class);
        Class<?> copy = loader.loadClass(UnloadableTriage.class.getName());
        assertNotSame(UnloadableTriage.class, copy);
        assertSame(decisions.of(copy), decisions.of(copy));
        return new WeakReference<>(loader);
    }

    private static void assertCollected(WeakReference<ClassLoader> loader) throws InterruptedException {
        for (int attempt = 0; attempt < 50 && loader.get() != null; attempt++) {
            System.gc();
            Thread.sleep(20);
        }
        assertNull(loader.get(), "The class loader of a type read by the cache is still reachable");
    }

    /** Defines its own copy of one class and delegates every other class to the test's loader. */
    private static final class SingleClassLoader extends ClassLoader {

        private final String name;

        private final byte[] bytes;

        SingleClassLoader(Class<?> type) throws IOException {
            super(type.getClassLoader());
            this.name = type.getName();
            try (InputStream in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
                this.bytes = in.readAllBytes();
            }
        }

        @Override
        protected Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
            if (!className.equals(name)) {
                return super.loadClass(className, resolve);
            }
            synchronized (getClassLoadingLock(className)) {
                Class<?> loaded = findLoadedClass(className);
                return loaded != null ? loaded : defineClass(className, bytes, 0, bytes.length);
            }
        }
    }
}

// Top level, so a copy defined by another class loader has no enclosing class to match.
record UnloadableTriage(@PropositionQuestion(asking = "Does this ticket convey urgency?") boolean urgent) {
}
