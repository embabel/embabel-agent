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
package com.embabel.agent.core.identity

/**
 * Identities associated with an agent process.
 * @param forUser the user for whom the process is running. Can be null.
 * @param runAs the user under which the process is running. Can be null.
 */
data class Identities
@JvmOverloads
constructor(
    val forUser: User? = null,
    val runAs: User? = null,
) {

    fun withForUser(forUser: User?): Identities =
        this.copy(forUser = forUser)

    fun withRunAs(runAs: User?): Identities =
        this.copy(runAs = runAs)

    companion object {

        @JvmField
        val DEFAULT = Identities()

    }

}
