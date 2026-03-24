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
package com.embabel.agent.api.termination

import com.embabel.agent.api.common.TerminationScope
import com.embabel.agent.api.common.TerminationSignal
import com.embabel.agent.core.support.InMemoryBlackboard
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TerminationExtensionsTest {

    @Test
    fun `getTerminationSignal returns null when no signal set`() {
        val blackboard = InMemoryBlackboard()
        assertThat(blackboard.getTerminationSignal()).isNull()
    }

    @Test
    fun `getTerminationSignal returns signal when set`() {
        val blackboard = InMemoryBlackboard()
        val signal = TerminationSignal(TerminationScope.AGENT, "test reason")
        blackboard[TerminationSignal.BLACKBOARD_KEY] = signal

        assertThat(blackboard.getTerminationSignal()).isEqualTo(signal)
    }

    @Test
    fun `clearTerminationSignal removes signal`() {
        val blackboard = InMemoryBlackboard()
        blackboard[TerminationSignal.BLACKBOARD_KEY] = TerminationSignal(TerminationScope.AGENT, "test")

        blackboard.clearTerminationSignal()

        assertThat(blackboard.getTerminationSignal()).isNull()
    }
}
