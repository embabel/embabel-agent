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
package com.embabel.common.ai.model.observation

import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.ClassificationService
import com.embabel.common.ai.classification.ClassificationSpec
import io.micrometer.observation.ObservationRegistry
import org.jetbrains.annotations.ApiStatus

/**
 * Opt-in observations for classification, with the delegate's metadata and model family preserved.
 * Each call emits `embabel.ai.classification` with fixed `operation` and `outcome` tags and validates
 * the selected category against the request. Provider observations inherit the open call scope.
 * Payloads, model identifiers and raw exceptions are never added to these observations or debug logs.
 * Operational failures and thrown exceptions record a fixed, stackless error marker with no cause.
 * The original exception is rethrown unchanged; the wrapper never changes thread interruption state.
 * Non-fatal observation lifecycle exceptions use bounded diagnostics when logging is available and
 * never replace service behavior. JVM error types propagate.
 */
@ApiStatus.Experimental
class ObservedClassificationService @JvmOverloads constructor(
    private val delegate: ClassificationService,
    observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
) : ClassificationService by delegate {
    private val observation = ServiceCallObservation(observationRegistry)

    override fun classify(request: ClassificationRequest): ClassificationResult =
        observation.classify(request) { delegate.classify(request) }

    // Interface delegation would forward this straight to the delegate and skip the observation.
    override fun classify(input: String, spec: ClassificationSpec): ClassificationResult =
        classify(ClassificationRequest.of(input, spec))
}