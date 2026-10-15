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
package com.embabel.common.ai.decision

/**
 * Throws for a JSON member that a decision type doesn't define. Every decision type has a private
 * any-setter that calls this. Jackson passes an unknown member to the any-setter whatever the
 * mapper's FAIL_ON_UNKNOWN_PROPERTIES setting is.
 *
 * @param type the name of the type being read
 * @param name the unknown member's name
 * @param value the unknown member's value
 * @throws IllegalArgumentException always
 */
internal fun rejectUnknownMember(type: String, name: String, value: Any?): Nothing =
    throw IllegalArgumentException("Unknown member '$name' in $type (${jsonKind(value)})")

/**
 * Names the JSON kind of a rejected member. The value itself stays out of the message.
 *
 * @param value the rejected member's value
 * @return a short description of its JSON kind
 */
private fun jsonKind(value: Any?): String = when (value) {
    null -> "null"
    is String -> "a string"
    is Number -> "a number"
    is Boolean -> "a boolean"
    is Map<*, *> -> "an object"
    is Collection<*> -> "an array"
    else -> "a value"
}
