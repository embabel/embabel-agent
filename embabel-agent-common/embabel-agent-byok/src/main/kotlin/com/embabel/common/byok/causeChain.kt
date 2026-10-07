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

/**
 * Returns the first exception of type [T] among this exception and its causes, or null if there
 * is none.
 *
 * It looks at this exception, then its cause, then that cause's cause. The search stops at an
 * exception that has no cause.
 *
 * The factories use it to find the provider SDK's exception, which holds the HTTP status code of
 * the provider's response. That exception may be the one the factory caught, or one of its causes.
 */
inline fun <reified T : Throwable> Throwable.firstOfType(): T? =
    generateSequence(this) { it.cause }
        .filterIsInstance<T>()
        .firstOrNull()
