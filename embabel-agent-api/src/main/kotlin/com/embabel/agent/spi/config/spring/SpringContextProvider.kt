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
package com.embabel.agent.spi.config.spring

import com.embabel.agent.api.annotation.support.ContextProvider
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Component

/**
 * Spring implementation of [ContextProvider] that retrieves objects from the
 * Spring [ApplicationContext].
 *
 * This enables injection of Spring beans into action methods using the
 * [com.embabel.agent.api.annotation.Provided] annotation.
 *
 * Example:
 * ```kotlin
 * @State
 * data class MyState(val data: String) {
 *     @Action
 *     fun process(@Provided myService: MyService): NextState {
 *         return NextState(myService.transform(data))
 *     }
 * }
 * ```
 *
 * @param applicationContext the Spring application context to retrieve beans from
 * @see ContextProvider
 * @see com.embabel.agent.api.annotation.Provided
 */
@Component
class SpringContextProvider(
    private val applicationContext: ApplicationContext,
) : ContextProvider {

    override fun <T : Any> getFromContext(type: Class<T>): T? {
        return try {
            applicationContext.getBean(type)
        } catch (e: NoSuchBeanDefinitionException) {
            null
        }
    }

    override fun hasInContext(type: Class<*>): Boolean {
        return applicationContext.getBeanNamesForType(type).isNotEmpty()
    }
}
