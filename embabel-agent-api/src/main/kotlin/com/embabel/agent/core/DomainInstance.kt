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
package com.embabel.agent.core

/**
 * A blackboard value whose identity is a [DomainType] (typically a
 * [DynamicType] declared in YAML, not a JVM class) plus a property map
 * carrying the actual data.
 *
 * Implementing this interface gives a value two things the platform
 * couldn't otherwise see:
 *
 *  - **Type identity beyond the JVM hierarchy.** [satisfiesType] consults
 *    [domainType] (and its parents) so a value carried by a generic JVM
 *    class can satisfy preconditions and action inputs by the
 *    pack-declared type name — `it:HubSpotContactCreated` resolves to
 *    a value whose JVM class is some carrier, not a `HubSpotContactCreated`
 *    JVM class.
 *
 *  - **Uniform property access.** Templating, validation, and other
 *    introspection can read [properties] without downcasting to a
 *    specific carrier class. The keys correspond to the type's
 *    [DomainType.properties] (plus framework-required fields like
 *    `id`, `occurredAt` for signal-shaped types).
 */
interface DomainInstance {

    val domainType: DomainType

    val properties: Map<String, Any?>
}

/**
 * Reserved key on a `Map<String, Any?>` blackboard value carrying its
 * declared [DomainType] name. Lets a value participate in type-aware
 * matching ([satisfiesType]) without a carrier class — useful for
 * pack-authored tools, sandbox script returns, and other emitters that
 * don't want to define a Kotlin class. Value is the type's [DomainType.name].
 */
const val TYPE_NAME_KEY: String = "_typeName"

/**
 * Reserved key on a `Map<String, Any?>` blackboard value carrying the
 * flattened ancestor type names (self + parents + grandparents…), used by
 * [satisfiesType] to match preconditions on parent types. Value is an
 * `Iterable<String>` of names. Optional — when absent, only [TYPE_NAME_KEY]
 * is consulted, which means the map only satisfies its own type, not its
 * ancestors. Emitters that know the type's parent chain should populate
 * this so the value behaves like a [DomainInstance] for inheritance.
 */
const val TYPE_LABELS_KEY: String = "_typeLabels"
