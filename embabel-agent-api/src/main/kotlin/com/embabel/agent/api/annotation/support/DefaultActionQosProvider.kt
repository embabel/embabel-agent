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
package com.embabel.agent.api.annotation.support

import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.core.ActionQos
import com.embabel.agent.core.ActionRetryPolicy
import com.embabel.agent.core.DelayPolicy
import com.embabel.agent.spi.config.spring.AgentPlatformProperties
import org.springframework.stereotype.Component
import java.lang.reflect.Method

/**
 * Default {@link com.embabel.agent.api.annotation.support.ActionQosProvider} implementation that resolves
 * retry overrides from {@link com.embabel.agent.api.annotation.Agent} and {@link com.embabel.agent.api.annotation.Action},
 * then maps them to {@link com.embabel.agent.core.ActionQos}.
 */
@Component
class DefaultActionQosProvider(
    val actionQosProperties: AgentPlatformProperties.ActionQosProperties = AgentPlatformProperties.ActionQosProperties(),
    val propertyProvider: ActionQosPropertyProvider = ActionQosPropertyProvider(),
) : ActionQosProvider {

    override fun provideActionQos(
        method: Method,
        instance: Any
    ): ActionQos {

        var defaultActionQos = actionQosProperties.default

        var agentDelay: DelayPolicy = DelayPolicy.SameAsAgent
        var props = instance.javaClass.getAnnotation(Agent::class.java)?.let {
            if (hasRetryExpression(it.actionRetryPolicyExpression)) {
                defaultActionQos = defaultActionQos
                    .overridingNotNull(getBound(it.actionRetryPolicyExpression))
            }
            if (it.actionRetryPolicy == ActionRetryPolicy.FIRE_ONCE) {
                defaultActionQos = defaultActionQos.copy(maxAttempts = 1)
            }
            agentDelay = DelayPolicy.of(it.delay)
            defaultActionQos
        } ?: defaultActionQos

        method.getAnnotation(Action::class.java)?.let {
            if (hasRetryExpression(it.actionRetryPolicyExpression)) {
                props = props.overridingNotNull(getBound(it.actionRetryPolicyExpression))
            }
            if (it.actionRetryPolicy == ActionRetryPolicy.FIRE_ONCE) {
                props = props.copy(maxAttempts = 1)
            }
        }

        // -1L is the sentinel meaning "not set at action level"; null propagates agent-level delay.
        // 0 is explicit "no delay" and overrides the agent default, so use null (not DelayPolicy.SameAsAgent)
        // as the "not set" signal — otherwise delayMs=0 and delayMs=-1 are indistinguishable.
        val actionDelay: DelayPolicy? = method.getAnnotation(Action::class.java)?.let {
            val ms = it.delayMs
            if (ms >= 0L) DelayPolicy.of(ms) else null
        }

        val delayPolicy = actionDelay ?: agentDelay

        return props.toActionQos().copy(delayPolicy = delayPolicy)
    }


    fun getBound(expr: String): AgentPlatformProperties.ActionQosProperties.ActionProperties? {
        return propertyProvider.getBound(expr)
    }

    private fun hasRetryExpression(expr: String): Boolean = expr.isNotBlank()
}
