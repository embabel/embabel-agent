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
package com.embabel.agent.spi.support

import com.embabel.agent.decision.DecisionExecutionContext
import com.embabel.common.ai.model.ModelSelectionContextHolder
import org.jetbrains.annotations.ApiStatus
import java.util.concurrent.Callable

/**
 * Carries the submitting thread's agent process and model-selection context into decision work.
 *
 * Both scopes restore the executing thread's previous values, including when provider work fails
 * or the model executes a caller-bound provider.
 */
@ApiStatus.Experimental
class DecisionExecutionContextBridge : DecisionExecutionContext {
    override fun <T> wrap(work: Callable<T>): Callable<T> {
        val agentProcess = AgentProcessAccessor.getValue()
        val modelSelectionContext = ModelSelectionContextHolder.get()
        return Callable {
            ModelSelectionContextHolder.with(modelSelectionContext) {
                AgentProcessAccessor.with(agentProcess) {
                    work.call()
                }
            }
        }
    }
}
