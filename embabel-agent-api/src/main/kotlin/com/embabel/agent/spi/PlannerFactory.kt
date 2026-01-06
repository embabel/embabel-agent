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
package com.embabel.agent.spi

import com.embabel.agent.core.ProcessOptions
import com.embabel.plan.Plan
import com.embabel.plan.Planner
import com.embabel.plan.PlanningSystem
import com.embabel.plan.WorldState
import com.embabel.plan.common.condition.WorldStateDeterminer

/**
 * Pluggable planner factory
 */
fun interface PlannerFactory {

    /**
     * Create a planner for this process
     */
    fun createPlanner(
        processOptions: ProcessOptions,
        worldStateDeterminer: WorldStateDeterminer,
    ): Planner<out PlanningSystem, out WorldState, out Plan>
}
