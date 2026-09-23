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
package com.embabel.agent.decision

import org.jetbrains.annotations.ApiStatus
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Receipt for decision models created and registered by one initializer.
 *
 * Depending on this receipt establishes initialization order. Closing it closes exactly the
 * facades that initializer created; user-provided models retain their ordinary owner and lifecycle.
 */
@ApiStatus.Experimental
class DecisionModelInitialization(createdModels: List<DecisionModel>) : AutoCloseable {
    val createdModels: List<DecisionModel> = Collections.unmodifiableList(ArrayList(createdModels.distinct()))
    private val closed = AtomicBoolean()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            createdModels.forEach(DecisionModel::close)
        }
    }
}
