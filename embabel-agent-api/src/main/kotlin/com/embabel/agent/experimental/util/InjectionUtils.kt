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
package com.embabel.agent.experimental.util

import org.springframework.beans.factory.config.AutowireCapableBeanFactory
import org.springframework.context.ApplicationContext
import org.springframework.lang.NonNull
import org.springframework.lang.Nullable

/**
 * Allows injected objects to specify additional injectees so that injection can
 * cascade without the injecting repository knowing the full structure of the object
 * graph.
 */
interface Injectable {

    /**
     * Additional objects to inject. Typically relationships of this object.
     *
     * @return list of objects to inject
     */
    fun additionalInjectees(): List<*>
}

/**
 * Injection utils for Spring
 */
object InjectionUtils {

    /**
     * Inject the given object with Spring. Return the injected object
     *
     * @param t object to inject
     */
    @JvmStatic
    fun <T> wire(@Nullable t: T?, @NonNull applicationContext: ApplicationContext): T? {
        if (t == null) {
            return null
        }
        val acbf = applicationContext.autowireCapableBeanFactory
        acbf.autowireBeanProperties(t, AutowireCapableBeanFactory.AUTOWIRE_NO, false)
        acbf.initializeBean(t, "%s:%d".format(t.javaClass.name, t.hashCode()))
        if (t is Injectable) {
            for (o in t.additionalInjectees()) {
                wire(o, applicationContext)
            }
        }
        return t
    }
}
