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
package com.embabel.common.byok

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Returns the first exception of type [T] among this exception and its causes, or null if there
 * is none.
 *
 * It looks at this exception, then its cause, then that cause's cause. The search stops at an
 * exception that has no cause. It also stops at an exception it has already looked at. That
 * happens when the causes form a loop, for example when A is caused by B and B is caused by A.
 *
 * The factories use it to find the provider SDK's exception, which holds the HTTP status code of
 * the provider's response. That exception may be the one the factory caught, or one of its causes.
 *
 * It is public because the OpenAI and Anthropic factories call it, and they are in other modules.
 * It is in this module because both of those modules already depend on it.
 */
inline fun <reified T : Throwable> Throwable.firstOfType(): T? {
    // The exceptions already looked at. The set compares by identity (===), not by equals(), so
    // two different exceptions are never taken for the same one.
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    return generateSequence(this) { it.cause }
        // seen.add returns false for an exception that is already in the set. takeWhile then
        // ends the sequence, so a loop of causes cannot run forever.
        .takeWhile { seen.add(it) }
        .filterIsInstance<T>()
        .firstOrNull()
}
