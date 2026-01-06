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
package com.embabel.agent.channel

import com.embabel.agent.api.channel.DevNullOutputChannel
import com.embabel.agent.api.channel.MulticastOutputChannel
import com.embabel.agent.api.channel.OutputChannel
import com.embabel.agent.api.channel.OutputChannelEvent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OutputChannelTest {

    @Test
    fun `plus operator with DevNull and regular channel returns the regular channel`() {
        val regularChannel = TestOutputChannel("test")

        val result1 = DevNullOutputChannel + regularChannel
        val result2 = regularChannel + DevNullOutputChannel

        assertEquals(regularChannel, result1)
        assertEquals(regularChannel, result2)
    }

    @Test
    fun `plus operator with two DevNull channels returns DevNull`() {
        val result = DevNullOutputChannel + DevNullOutputChannel

        assertEquals(DevNullOutputChannel, result)
    }

    @Test
    fun `plus operator with two different regular channels returns MulticastOutputChannel`() {
        val channel1 = TestOutputChannel("channel1")
        val channel2 = TestOutputChannel("channel2")

        val result = channel1 + channel2

        assertTrue(result is MulticastOutputChannel)
    }

    @Test
    fun `plus operator creates MulticastOutputChannel that sends to all channels`() {
        val channel1 = TestOutputChannel("channel1")
        val channel2 = TestOutputChannel("channel2")
        val event = TestOutputChannelEvent("test-process")

        val multicastChannel = channel1 + channel2
        multicastChannel.send(event)

        assertEquals(1, channel1.receivedEvents.size)
        assertEquals(1, channel2.receivedEvents.size)
        assertEquals(event, channel1.receivedEvents[0])
        assertEquals(event, channel2.receivedEvents[0])
    }

    @Test
    fun `plus operator is associative - different groupings produce equivalent behavior`() {
        val channel1 = TestOutputChannel("channel1")
        val channel2 = TestOutputChannel("channel2")
        val channel3 = TestOutputChannel("channel3")
        val event = TestOutputChannelEvent("test-process")

        val result1 = (channel1 + channel2) + channel3
        val result2 = channel1 + (channel2 + channel3)

        result1.send(event)
        assertEquals(1, channel1.receivedEvents.size)
        assertEquals(1, channel2.receivedEvents.size)
        assertEquals(1, channel3.receivedEvents.size)

        channel1.receivedEvents.clear()
        channel2.receivedEvents.clear()
        channel3.receivedEvents.clear()

        result2.send(event)
        assertEquals(1, channel1.receivedEvents.size)
        assertEquals(1, channel2.receivedEvents.size)
        assertEquals(1, channel3.receivedEvents.size)
    }

    @Test
    fun `DevNullOutputChannel sends warning to logger`() {
        val event = TestOutputChannelEvent("test-process")

        assertDoesNotThrow {
            DevNullOutputChannel.send(event)
        }
    }

    @Test
    fun `MulticastOutputChannel handles exceptions from individual channels gracefully`() {
        val workingChannel = TestOutputChannel("working")
        val failingChannel = FailingOutputChannel()
        val event = TestOutputChannelEvent("test-process")

        val multicastChannel = workingChannel + failingChannel

        assertDoesNotThrow {
            multicastChannel.send(event)
        }

        assertEquals(1, workingChannel.receivedEvents.size)
        assertEquals(event, workingChannel.receivedEvents[0])
    }

    @Test
    fun `plus operator with multiple DevNull combinations`() {
        val regularChannel = TestOutputChannel("test")

        val result1 = DevNullOutputChannel + DevNullOutputChannel + regularChannel
        val result2 = regularChannel + DevNullOutputChannel + DevNullOutputChannel
        val result3 = DevNullOutputChannel + regularChannel + DevNullOutputChannel

        assertEquals(regularChannel, result1)
        assertEquals(regularChannel, result2)
        assertEquals(regularChannel, result3)
    }
}

class TestOutputChannel(private val name: String) : OutputChannel {
    val receivedEvents = mutableListOf<OutputChannelEvent>()

    override fun send(event: OutputChannelEvent) {
        receivedEvents.add(event)
    }

    override fun equals(other: Any?): Boolean {
        return other is TestOutputChannel && other.name == this.name
    }

    override fun hashCode(): Int {
        return name.hashCode()
    }

    override fun toString(): String {
        return "TestOutputChannel($name)"
    }
}

class FailingOutputChannel : OutputChannel {
    override fun send(event: OutputChannelEvent) {
        throw RuntimeException("This channel always fails")
    }
}

data class TestOutputChannelEvent(override val processId: String) : OutputChannelEvent
