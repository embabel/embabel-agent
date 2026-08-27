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
package com.embabel.agent.spi

/**
 * Marks an [com.embabel.common.ai.model.EmbeddingService] that stands in for a model this
 * deployment does not yet have a key for.
 *
 * A placeholder embeds nothing. Its only job is to give the platform something to resolve before
 * any key is supplied, so that a BYOK application using RAG or memory starts with zero keys, the
 * same as one that only chats — the counterpart of [PlaceholderLlmService].
 *
 * ## A placeholder must not report a dimension
 *
 * This is the difference between this marker and the LLM one, and the reason the marker has to be
 * checked rather than merely caught.
 *
 * There is no defensible dimension for a placeholder to return. Any number would be used to create
 * a vector index; writes to that index would succeed; and a real model configured later would
 * silently disagree with everything already stored. The failure would surface as bad search results
 * rather than as an error — the worst kind, because nothing points at the cause.
 *
 * So `dimensions` fails like every other call. A consumer that provisions a vector index must
 * therefore ask **whether there is a model yet** and skip, rather than call `dimensions` and handle
 * the exception: catching means the decision is made from a failure, and a failure cannot be told
 * apart from a provider that is merely unreachable right now.
 *
 * Ask it through [com.embabel.common.ai.model.EmbeddingService.awaitingProviderKey], NOT by testing for
 * this interface. Wrapping is common — event tracking decorates the configured service,
 * applications add layers to hot-swap the model or meter it — and a wrapper around a placeholder is
 * not itself a placeholder, so a type test answers about the outermost layer and reports "there is
 * a model". The property rides through Kotlin's `by` delegation to any depth. This interface marks
 * the implementation; the property is the question.
 *
 * Skipping is recoverable. The index is created once a real model is registered — on the next
 * boot, or sooner for a consumer that re-checks, since reading the property costs nothing to
 * repeat. A consumer that wants recovery without a restart must re-resolve the service rather than
 * hold the reference it was given, since the one it holds is the placeholder and stays so.
 *
 * Implemented by `SetupRequiredEmbedding` in `embabel-agent-byok-autoconfigure`. It lives here,
 * next to [PlaceholderLlmService], because `com.embabel.common.ai.model.ConfigurableModelProvider`
 * has to recognise a placeholder without depending on the BYOK module.
 */
interface PlaceholderEmbeddingService
