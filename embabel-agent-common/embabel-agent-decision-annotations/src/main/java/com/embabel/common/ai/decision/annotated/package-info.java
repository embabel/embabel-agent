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
/**
 * Reads decision specs from annotated Java types.
 * <p>
 * A record or bean declares its questions with {@link com.embabel.common.ai.decision.annotated.PropositionQuestion},
 * {@link com.embabel.common.ai.decision.annotated.ChoiceQuestion} and
 * {@link com.embabel.common.ai.decision.annotated.RatingQuestion}. Question names, option ids,
 * level ids and question order come from the Jackson mapper that reads the type. The result is an
 * ordinary {@link com.embabel.common.ai.decision.DecisionSpec}, equal to one declared with the
 * builder. An enum annotated with {@link com.embabel.common.ai.decision.annotated.Classification}
 * reads as a {@link com.embabel.common.ai.classification.CategoryMapping}.
 * <p>
 * This package is a prototype. The module that holds it is optional and is not published.
 */
package com.embabel.common.ai.decision.annotated;
