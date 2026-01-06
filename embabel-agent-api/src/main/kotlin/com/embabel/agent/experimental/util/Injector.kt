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

import org.springframework.beans.factory.annotation.Configurable
import org.springframework.context.ApplicationContext
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.stereotype.Component

/**
 * Spring-inject entities if they have an @Configurable annotation. Can be put on any
 * entity without efficiency concerns.
 */
@Component
class Injector(private val applicationContext: ApplicationContext) {

    /**
     * Inject the given object if it is non null and annotated
     * with @Configurable.
     * @param target the object to inject. may be null
     */
    fun inject(target: Any?) {
        if (target == null) {
            return
        }
        val atConfigurable = AnnotatedElementUtils.findMergedAnnotation(target.javaClass, Configurable::class.java)
        if (atConfigurable == null) {
            return
        }
        InjectionUtils.wire(target, applicationContext)
    }
}
