/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.api.common.autonomy

import com.embabel.agent.core.Goal
import com.embabel.agent.core.ProcessOptions
import com.embabel.plan.Plan

/**
 * Interface for listing achievable plans based on the current world state.
 */
interface PlanLister {

    /**
     * List achievable plans from the current world state.
     * @param processOptions process options
     * @param bindings bindings to use for planning
     */
    fun achievablePlans(
        processOptions: ProcessOptions,
        bindings: Map<String, Any>,
    ): List<Plan>

    fun achievableGoals(
        processOptions: ProcessOptions,
        bindings: Map<String, Any>,
    ): List<Goal> =
        achievablePlans(processOptions, bindings).mapNotNull { it.goal as? Goal }
}
