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
package com.embabel.agent.shell.personality.hitchhiker

import com.embabel.agent.shell.MessageGeneratorPromptProvider
import com.embabel.agent.spi.logging.personality.hitchhiker.HitchhikerColorPalette
import com.embabel.common.util.RandomFromFileMessageGenerator
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component


val GuideEntries = listOf(
    "DON'T PANIC",
    "Mostly Harmless",
    "The Restaurant at the End of the Universe",
    "Life, the Universe and Everything",
    "So Long, and Thanks for All the Fish",
    "Milliways",
    "Infinite Improbability Drive",
    "Vogon Constructor Fleet",
    "Magrathea",
    "Heart of Gold",
    "Babel Fish",
    "Pan Galactic Gargle Blaster",
    "Trillian",
    "Zaphod Beeblebrox",
    "Arthur Dent",
    "Ford Prefect",
    "Marvin the Paranoid Android",
    "Deep Thought",
    "42",
)

@Component
@ConditionalOnProperty(name = ["embabel.agent.logging.personality"], havingValue = "hitchhiker")
class HitchhikerPromptProvider : MessageGeneratorPromptProvider(
    color = HitchhikerColorPalette.BABEL_GREEN,
    prompt = GuideEntries.random(),
    messageGenerator = RandomFromFileMessageGenerator(
        url = "logging/hitchhiker.txt"
    ),
)
