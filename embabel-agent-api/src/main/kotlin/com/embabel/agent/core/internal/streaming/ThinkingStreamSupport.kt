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
package com.embabel.agent.core.internal.streaming

import com.embabel.common.ai.converters.streaming.StreamingLineClassifier
import com.embabel.common.core.streaming.StreamingEvent
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Applies the same newline-based thinking classification used by object streaming,
 * while intentionally omitting object creation. Non-JSON lines are emitted as
 * [StreamingEvent.Thinking]; JSON lines and bare code fences are dropped.
 *
 * Line-assembly state is created inside [Flux.defer], keeping repeated and
 * concurrent subscriptions isolated.
 */
internal fun Flux<String>.toThinkingEvents(): Flux<StreamingEvent<String>> =
    Flux.defer {
        val buffer = StringBuilder()
        this@toThinkingEvents
            .concatMap { chunk ->
                buffer.append(chunk)
                val lines = mutableListOf<String>()
                var newline = buffer.indexOf("\n")
                while (newline >= 0) {
                    buffer.substring(0, newline).trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let(lines::add)
                    buffer.delete(0, newline + 1)
                    newline = buffer.indexOf("\n")
                }
                Flux.fromIterable(lines)
            }
            .concatWith(
                Mono.fromSupplier { buffer.toString().trim() }
                    .filter { it.isNotEmpty() },
            )
            .concatMap(StreamingLineClassifier::classify)
    }
