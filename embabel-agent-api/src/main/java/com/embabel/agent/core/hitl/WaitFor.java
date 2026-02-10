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
package com.embabel.agent.core.hitl;

import org.springframework.lang.NonNull;

/**
 * Java syntax sugar for HITL
 *
 * @see WaitKt
 */
public class WaitFor {

    private WaitFor() {
        // Prevent instantiation
    }

    /**
     * @see com.embabel.agent.api.annotation.WaitKt#fromForm(String, Class)
     */
    @NonNull
    public static <T> T formSubmission(@NonNull String title, @NonNull Class<T> clazz) {
        return WaitKt.fromForm(title, clazz);
    }

    @NonNull
    public static <P> P confirmation(@NonNull P what, @NonNull String description) {
        return WaitKt.confirm(what, description);
    }

    @NonNull
    public static <P> P awaitable(@NonNull Awaitable<P, ?> awaitable) {
        return WaitKt.waitFor(awaitable);
    }

}
