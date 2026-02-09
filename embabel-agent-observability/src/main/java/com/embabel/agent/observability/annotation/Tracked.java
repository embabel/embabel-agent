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
package com.embabel.agent.observability.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method for automatic observability tracking.
 * Creates a span capturing inputs, outputs, duration, and errors.
 * When called within an agent process, the span is enriched with
 * runId and agent name from the current {@code AgentProcess}.
 *
 * <p>Example usage:
 * <pre>{@code
 * @Tracked(value = "enrichCustomer", type = TrackType.PROCESSING)
 * public Customer enrich(Customer input) {
 *     // ...
 * }
 * }</pre>
 *
 * @since 0.3.4
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Tracked {

    /**
     * Name for the tracked operation. Defaults to the method name if empty.
     *
     * @return the operation name
     */
    String value() default "";

    /**
     * Classification of the tracked operation.
     *
     * @return the track type
     */
    TrackType type() default TrackType.CUSTOM;

    /**
     * Optional description of what this operation does.
     *
     * @return the description
     */
    String description() default "";
}
