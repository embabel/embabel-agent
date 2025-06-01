/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.examples.simple.horoscope

import com.embabel.agent.domain.library.RelevantNewsStories
import com.embabel.agent.testing.UnitTestUtils
import com.embabel.examples.simple.horoscope.kotlin.Horoscope
import com.embabel.examples.simple.horoscope.kotlin.StarNewsFinder
import com.embabel.examples.simple.horoscope.kotlin.StarPerson
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class FakeHoroscopeService : HoroscopeService {

    override fun dailyHoroscope(sign: String): String {
        return "you will be chased by wolves"
    }
}


/**
 * Demonstrates unit testing of an agent
 */
class StarNewsFinderTest {

    @Nested
    inner class Writeup {

        val horoscopeService = FakeHoroscopeService()

        @Test
        fun `writeup must contain person's name`() {
            val starNewsFinder = StarNewsFinder(horoscopeService = horoscopeService, storyCount = 5, wordCount = 100)
            val starPerson = StarPerson(name = "Rod", sign = "Cancer")
            val relevantNewsStories = RelevantNewsStories(emptyList())
            val llmCall = UnitTestUtils.captureLlmCall {
                starNewsFinder.starNewsWriteup(
                    person = starPerson,
                    relevantNewsStories = relevantNewsStories,
                    horoscope = Horoscope(horoscopeService.dailyHoroscope("Cancer")),
                )
            }
            assertTrue(llmCall.prompt.contains(starPerson.name))
        }
    }

}
