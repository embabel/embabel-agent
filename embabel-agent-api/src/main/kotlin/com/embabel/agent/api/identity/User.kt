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
package com.embabel.agent.api.identity

/**
 * Superinterface for all users in the system.
 * displayName and username properties can default to id
 * if an implementation doesn't know how to populate them,
 * but they allow consistent experience.
 */
interface User {

    /**
     * User's id in this system. Embabel-owned, stable.
     * Additional keys will be added for other systems like Discord
     */
    val id: String

    val displayName: String

    val username: String

    val email: String?
}

/**
 * Convenient implementation class for a user
 */
data class SimpleUser(
    override val id: String,
    override val displayName: String,
    override val username: String,
    override val email: String?
) : User
