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
package com.embabel.common.ai.decision

import org.jetbrains.annotations.ApiStatus

/**
 * Thrown when a decision service cannot answer a request: a question kind is missing from its
 * capabilities, or the request exceeds a limit the service reports.
 *
 * The check runs before any provider call, so no model work has happened when this is thrown. The
 * message names the service, the questions concerned with their kinds, the missing kind or the
 * exceeded limit, the service's capabilities and a remedy. It holds question names and kinds only.
 * The input, instructions, options and levels stay out of it.
 *
 * @param message the description of the unsupported request
 */
@ApiStatus.Experimental
class UnsupportedDecisionException(message: String) : UnsupportedOperationException(message)
