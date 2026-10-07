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
package com.embabel.common.ai.decision.spi

import org.jetbrains.annotations.ApiStatus

/**
 * A process-wide switch that lets decision execution log request and response content at TRACE.
 *
 * The switch is off by default. Content is logged only when the switch is on and the logger
 * concerned is at TRACE. The switch alone logs nothing, and TRACE alone logs no content.
 *
 * Captured lines hold the input, the question instructions, options and levels, and provider
 * output. They can contain personal or confidential text. Turn the switch on only for local
 * diagnosis.
 */
@ApiStatus.Experimental
object DecisionContentCapture {

    @Volatile
    private var enabled = false

    /** Turns content capture on for the whole process. */
    @JvmStatic
    fun enable() {
        enabled = true
    }

    /** Turns content capture off for the whole process. */
    @JvmStatic
    fun disable() {
        enabled = false
    }

    /**
     * Reports whether content capture is on.
     *
     * @return true when content capture is on
     */
    @JvmStatic
    fun isEnabled(): Boolean = enabled
}
