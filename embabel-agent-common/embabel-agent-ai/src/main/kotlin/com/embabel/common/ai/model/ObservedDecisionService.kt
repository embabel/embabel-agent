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
package com.embabel.common.ai.model

import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import io.micrometer.observation.ObservationRegistry
import org.jetbrains.annotations.ApiStatus

/**
 * Opt-in observations for decision services, preserving the delegate's DECISION metadata snapshot.
 * Classification emits one `embabel.ai.classification` observation; proposition assessment emits one
 * `embabel.ai.decision` observation. Both carry only fixed `operation` and `outcome` tags. False is an
 * `answered` outcome, just like true. Operational failures and exceptions use a fixed, stackless
 * error marker with no cause. Original exceptions are rethrown unchanged and never logged.
 * Thread interruption state is untouched.
 */
@ApiStatus.Experimental
class ObservedDecisionService @JvmOverloads constructor(
    private val delegate: DecisionService,
    observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
) : DecisionService by delegate {
    private val observation = ServiceCallObservation(observationRegistry)

    override fun classify(request: ClassificationRequest): ClassificationResult =
        observation.classify(request) { delegate.classify(request) }

    override fun assess(request: PropositionRequest): PropositionResult = observation.assess { delegate.assess(request) }
}
