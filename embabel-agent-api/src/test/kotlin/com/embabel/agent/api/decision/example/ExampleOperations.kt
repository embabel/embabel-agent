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
package com.embabel.agent.api.decision.example

import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.PlatformServices
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyProcessContext
import com.embabel.agent.test.unit.DummyAgent
import com.embabel.agent.test.unit.FakeOperationContext
import com.embabel.common.ai.model.DecisionServiceRegistry

/**
 * Builds operation contexts for the decision examples.
 */
object ExampleOperations {

    /**
     * Returns an operation context whose `ai()` selects decision services from the given registry.
     *
     * @param registry the registry the context's platform services return
     * @return an operation context for a test agent process
     */
    @JvmStatic
    fun withRegistry(registry: DecisionServiceRegistry): OperationContext {
        val base = dummyProcessContext(DummyAgent)
        val platformServices = RegistryPlatformServices(base.platformServices, registry)
        return FakeOperationContext(processContext = base.copy(platformServices = platformServices))
    }

    /** Platform services that return a fixed decision service registry. */
    private class RegistryPlatformServices(
        delegate: PlatformServices,
        private val registry: DecisionServiceRegistry,
    ) : PlatformServices by delegate {
        override fun decisionServices(): DecisionServiceRegistry = registry
    }
}
