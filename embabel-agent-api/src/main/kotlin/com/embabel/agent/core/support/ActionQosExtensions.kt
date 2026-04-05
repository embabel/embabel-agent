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
package com.embabel.agent.core.support

import com.embabel.agent.core.Action
import com.embabel.agent.core.ActionQos
import com.embabel.agent.spi.config.spring.AgentPlatformProperties

/**
 * Wraps an [Action], overriding only [Action.qos] while delegating every other
 * method to the original instance.
 *
 * Kotlin's `by` delegation generates all forwarding code at compile time — there
 * is no reflection and no runtime proxy overhead.  The original [Action] executes
 * unchanged; only the QoS used to build the retry template differs.
 */
private class QosOverridingAction(
    private val delegate: Action,
    override val qos: ActionQos,
) : Action by delegate

/**
 * Returns this [Action] with its QoS resolved against the platform-level
 * [AgentPlatformProperties.ActionQosProperties].
 *
 * ## Resolution rule
 * | Condition | Result |
 * |---|---|
 * | `action.qos == ActionQos()` | Platform defaults are applied — action was never explicitly configured |
 * | `action.qos != ActionQos()` | Original QoS is kept — action has an explicit configuration |
 *
 * This correctly handles all construction paths:
 * - **Annotation-based** (`@Agent` / `@Action`): already wired via
 *   [com.embabel.agent.api.annotation.support.DefaultActionQosProvider]; their
 *   `qos` will differ from `ActionQos()` when properties are set, so this
 *   function leaves them unchanged.
 * - **DSL actions** (`AgentBuilder.transformation`, `AgentBuilder.promptedTransformer`):
 *   default to `ActionQos()` unless the caller passes an explicit `qos` argument;
 *   platform defaults are applied here.
 * - **Workflow builder actions** (`RepeatUntil`, `ScatterGather`, etc.): never pass
 *   a `qos` argument, so always receive `ActionQos()`; platform defaults are applied.
 * - **Child processes**: go through the same `AbstractAgentProcess.executeAction()`
 *   choke point, so coverage is automatic.
 *
 * ## Why structural equality works
 * [ActionQos] is a Kotlin `data class`, so `ActionQos() == ActionQos()` is always
 * `true`.  Any action that received a default-constructed QoS is therefore correctly
 * identified as "not explicitly configured."  A developer who intentionally writes
 * `ActionQos(maxAttempts = 5)` — which happens to equal `ActionQos()` — would also
 * have platform defaults applied, but since the values are identical the runtime
 * behaviour is unchanged.
 *
 * ## No-op guarantee
 * If the platform properties resolve back to `ActionQos()` (all fields null, no
 * override configured), the original action is returned as-is — no allocation,
 * no wrapping.
 */
fun Action.withEffectiveQos(
    properties: AgentPlatformProperties.ActionQosProperties,
): Action {
    // Action has an explicit QoS — respect it unconditionally.
    if (qos != ActionQos()) return this

    val platformQos = properties.default.toActionQos()

    // Platform also has no override — nothing to do, avoid allocation.
    if (platformQos == ActionQos()) return this

    return QosOverridingAction(this, platformQos)
}
