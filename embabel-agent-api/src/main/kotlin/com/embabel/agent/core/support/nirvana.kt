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
package com.embabel.agent.core.support

import com.embabel.agent.core.Goal
import com.embabel.plan.utility.UtilityPlanner

/**
 * Goal available to all Utility planners. Reach nirvana, where there is nothing more to do.
 * It's important that this goal cannot be satisfied, to prevent the plan from completing prematurely.
 * A plan may complete by satisfying another goal, but not by reaching nirvana.
 */
val NIRVANA = Goal(
    name = UtilityPlanner.NIRVANA,
    description = "Nirvana: Nothing more to do",
    // Preconditions that cannot be satisfied
    pre = setOf("__unobtanium__"),
    outputType = null,
)
