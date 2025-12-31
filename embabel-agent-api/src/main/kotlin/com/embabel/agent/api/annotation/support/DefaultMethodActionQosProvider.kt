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
package com.embabel.agent.api.annotation.support

import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.core.ActionQos
import com.embabel.agent.spi.config.spring.AgentPlatformProperties
import org.springframework.stereotype.Component
import java.lang.reflect.Method

@Component
class DefaultMethodActionQosProvider(
    val perActionQosProperties: AgentPlatformProperties.ActionQosProperties = AgentPlatformProperties.ActionQosProperties()
) : MethodActionQosProvider {

    override fun provideActionQos(
        method: Method,
        instance: Any
    ): ActionQos {

        val defaultActionQos = perActionQosProperties.default.toActionQos()

        val props = instance.javaClass.getAnnotation(Agent::class.java)?.let {
            perActionQosProperties.agents[it.name]?.get(method.name)?.toActionQos(defaultActionQos)
                ?: perActionQosProperties.default.toActionQos(defaultActionQos)
        } ?: perActionQosProperties.default.toActionQos(defaultActionQos)

        if (method.isAnnotationPresent(Action::class.java)
            && method.getAnnotation(Action::class.java).retryAction.isNotEmpty()) {
            return method.getAnnotation(Action::class.java).retryAction.first()
                .let { retryAction ->
                    ActionQos(
                        retryAction.maxAttempts.firstOrNull() ?: props.maxAttempts,
                        retryAction.backoffMillis.firstOrNull() ?: props.backoffMillis,
                        retryAction.backoffMultiplier.firstOrNull() ?: props.backoffMultiplier,
                        retryAction.backoffMaxInterval.firstOrNull() ?: props.backoffMaxInterval,
                        retryAction.idempotent.firstOrNull() ?: props.idempotent
                    )
                }
        }

        return props
    }
}
