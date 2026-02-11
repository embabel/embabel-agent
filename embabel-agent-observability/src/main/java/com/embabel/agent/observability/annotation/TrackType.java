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

/**
 * Classification of tracked operations for observability.
 *
 * @since 0.3.4
 */
public enum TrackType {

    /** General-purpose custom tracking. */
    CUSTOM,

    /** Data processing operation. */
    PROCESSING,

    /** Validation or verification step. */
    VALIDATION,

    /** Data transformation operation. */
    TRANSFORMATION,

    /** Call to an external service or API. */
    EXTERNAL_CALL,

    /** Computation or calculation. */
    COMPUTATION
}
