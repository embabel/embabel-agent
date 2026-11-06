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
package com.embabel.agent.a2a

import io.opentelemetry.context.ContextKey

/**
 * JVM-local OTel context slot for the A2A session identifier.
 *
 * This key is stored in the OTel [io.opentelemetry.context.Context] and propagates
 * across virtual-thread hops within the JVM (when the context is explicitly captured
 * and restored — see [io.opentelemetry.context.Context.makeCurrent]).
 *
 * It is NEVER serialised into HTTP headers, so it cannot leak to LLM providers or
 * any other external service. Contrast with OTel baggage, which rides in a `baggage:`
 * HTTP header on every instrumented outbound call.
 *
 * contextId is propagated between A2A agents via the A2A message body field, not via
 * this key. The key is the in-JVM carrier that allows infrastructure code
 * ([com.embabel.agent.a2a.server.support.AutonomyA2ARequestHandler] →
 * [com.embabel.agent.a2a.client.EmbabelA2AClient]) to propagate contextId
 * transparently without requiring agent business code to thread it explicitly.
 *
 * Allocated once as a top-level constant. [ContextKey] uses identity equality —
 * a second call to [ContextKey.named] produces an independent key that cannot
 * read values stored under this one.
 */
internal val A2A_CONTEXT_ID_KEY: ContextKey<String> = ContextKey.named("a2a.context_id")
