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
package com.embabel.agent.spi.support.persistence

import com.embabel.agent.core.persistence.BlackboardEntryDeserializationContext
import com.embabel.agent.core.persistence.BlackboardEntrySerializationContext
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.core.support.InMemoryBlackboardState
import com.embabel.agent.core.support.InternalAgentStateApi

/**
 * Converts [InMemoryBlackboard] instances to and from durable snapshot models.
 *
 * This is support-layer code for the current in-memory blackboard implementation.
 * It preserves ordered entries, hidden entries, named bindings, and protected
 * keys without exposing those implementation details through the public
 * [com.embabel.agent.core.Blackboard] API.
 */
@OptIn(InternalAgentStateApi::class)
internal class InMemoryBlackboardSnapshotter(
    private val serializerResolver: BlackboardEntrySerializerResolver,
) {

    fun snapshot(
        blackboard: InMemoryBlackboard,
        processId: String? = null,
    ): BlackboardSnapshot {
        val state = blackboard.internalState()
        val entrySnapshots = state.entries.mapIndexed { index, value ->
            val sequence = index.toLong()
            BlackboardEntrySnapshot(
                entryId = entryId(state.blackboardId, sequence),
                sequence = sequence,
                value = serializerResolver.serialize(
                    value = value,
                    context = BlackboardEntrySerializationContext(
                        processId = processId,
                        entrySequence = sequence,
                    ),
                ),
                hidden = state.hiddenEntries.any { sameBlackboardValue(it, value) },
            )
        }
        val entrySnapshotReferences = state.entries
            .mapIndexed { index, value ->
                EntrySnapshotReference(
                    value = value,
                    snapshot = entrySnapshots[index],
                )
            }

        return BlackboardSnapshot(
            blackboardId = state.blackboardId,
            entries = entrySnapshots,
            bindings = state.bindings.mapValues { (key, value) ->
                // If a named binding points at an object already present in the
                // ordered entries list, store the entry id instead of duplicating
                // the value payload. Restore then reuses the same object instance
                // for both the binding and the blackboard entry.
                val linkedEntry = entrySnapshotReferences.firstOrNull { entryReference ->
                    sameBlackboardValue(entryReference.value, value)
                }?.snapshot
                BlackboardBindingSnapshot(
                    key = key,
                    value = linkedEntry?.value ?: serializerResolver.serialize(
                        value = value,
                        context = BlackboardEntrySerializationContext(
                            processId = processId,
                            key = key,
                        ),
                    ),
                    entryId = linkedEntry?.entryId,
                    protected = key in state.protectedKeys,
                )
            },
        )
    }

    fun restore(
        snapshot: BlackboardSnapshot,
        processId: String? = null,
    ): InMemoryBlackboard {
        val entriesById = snapshot.entries.associate { entry ->
            entry.entryId to serializerResolver.deserialize(
                value = entry.value,
                context = BlackboardEntryDeserializationContext(
                    processId = processId,
                    entrySequence = entry.sequence,
                ),
            )
        }
        val bindings = snapshot.bindings.mapValues { (key, binding) ->
            binding.entryId?.let { entriesById.getValue(it) }
                ?: serializerResolver.deserialize(
                    value = binding.value,
                    context = BlackboardEntryDeserializationContext(
                        processId = processId,
                        key = key,
                    ),
                )
        }
        val state = InMemoryBlackboardState(
            blackboardId = snapshot.blackboardId,
            bindings = bindings,
            entries = snapshot.entries.map { entriesById.getValue(it.entryId) },
            hiddenEntries = snapshot.entries
                .filter { it.hidden }
                .mapTo(mutableSetOf()) { entriesById.getValue(it.entryId) },
            protectedKeys = snapshot.bindings
                .filterValues { it.protected }
                .keys,
        )
        return InMemoryBlackboard(snapshot.blackboardId).apply {
            replaceInternalState(state)
        }
    }

    private fun entryId(
        blackboardId: String,
        sequence: Long,
    ): String = "$blackboardId:$sequence"

    private data class EntrySnapshotReference(
        val value: Any,
        val snapshot: BlackboardEntrySnapshot,
    )

    private fun sameBlackboardValue(
        left: Any,
        right: Any,
    ): Boolean =
        when {
            left.isJavaTimeValue() || right.isJavaTimeValue() -> left == right
            else -> left === right
        }

    private fun Any.isJavaTimeValue(): Boolean =
        javaClass.name.startsWith("java.time.")
}
