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
package com.embabel.common.ai.decision.systemone

import org.jetbrains.annotations.ApiStatus

/**
 * Thrown when a System One body can't be read as a whole, so no question can be answered from
 * it. The message never holds any of the body.
 */
@ApiStatus.Experimental
class SystemOneFormatException(message: String) : RuntimeException(message)
