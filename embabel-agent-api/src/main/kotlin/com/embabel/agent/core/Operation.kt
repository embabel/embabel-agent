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
package com.embabel.agent.core

import com.embabel.common.core.types.Described
import com.embabel.common.core.types.Named
import com.embabel.plan.goap.GoapStep

/**
 * Named operation in agent system: Action, Goal or Condition
 */
sealed interface Operation : Named


interface AgentSystemStep : GoapStep, Described, Operation {

    /**
     * Data inputs to this step.
     * Will be used to build preconditions,
     * in addition to explicit preconditions.
     */
    val inputs: Set<IoBinding>

}
