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
package com.embabel.agent.api.common

import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Tag interface to indicate that an implementing type should be built from the context from its bound fields.
 * Provides a strongly typed way to wait on combined results.
 * Makes a megazord!
 * An aggregation should have multiple non-nullable fields, each of which will be bound to the blackboard.
 * An aggregation is used as an input to the action.
 */
interface Aggregation

/**
 * Tag interface used as an action return type. Indicates that some of the fields will be bound to the blackboard.
 * Fields are usually nullable.
 */
interface SomeOf {

    companion object {

        /**
         * Fields of this SomeOf that are domain types and can be bound to the blackboard.
         */
        fun eligibleFields(
            outputClass: Class<*>,
        ): Set<Field> {
            return outputClass.declaredFields
                .filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }
                .toSet()
        }
    }
}
