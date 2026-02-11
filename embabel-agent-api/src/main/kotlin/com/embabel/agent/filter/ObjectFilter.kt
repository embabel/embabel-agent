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
package com.embabel.agent.filter

/**
 * Non-sealed extension point within the [PropertyFilter] sealed hierarchy.
 *
 * Allows modules outside agent-api to define their own filter types
 * that are still assignable to [PropertyFilter]. Subtypes participate
 * in the [PropertyFilter] type hierarchy but are not exhaustively matched
 * by `when` expressions on [PropertyFilter] in agent-api.
 *
 * Known subtypes:
 * - `EntityFilter` (in RAG module): Sealed hierarchy for entity-specific filtering (e.g., label matching)
 */
interface ObjectFilter : PropertyFilter
