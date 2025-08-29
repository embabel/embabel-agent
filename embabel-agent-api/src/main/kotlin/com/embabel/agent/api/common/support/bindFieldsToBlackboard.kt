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
package com.embabel.agent.api.common.support

import com.embabel.agent.api.common.SomeOf
import com.embabel.agent.core.Blackboard
import com.embabel.agent.core.IoBinding
import org.slf4j.Logger
import java.lang.reflect.Modifier

/**
 * Bind the fields of this object to the [Blackboard] if it is an instance of [SomeOf].
 */
fun destructureAndBindIfNecessary(
    obj: Any,
    name: String,
    blackboard: Blackboard,
    logger: Logger,
) {
    (obj as? SomeOf)?.bindFieldsToBlackboard(
        name = name,
        blackboard = blackboard,
        logger = logger
    )
}


private fun SomeOf.bindFieldsToBlackboard(
    name: String,
    blackboard: Blackboard,
    logger: Logger,
) {
    javaClass.declaredFields
        .filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }
        .forEach { field ->
            field.setAccessible(true)
            val fieldValue = field.get(this)
            if (fieldValue != null) {
                val bindingName = IoBinding.DEFAULT_BINDING // field.name
                blackboard[bindingName] = fieldValue
                logger.info(
                    "Binding output element of composite action {}: {} to {}",
                    name,
                    bindingName,
                    fieldValue,
                )
            } else {
                logger.info(
                    "Field {} in output of composite action {} is null, not binding",
                    field.name,
                    name,
                )
            }
        }
}
