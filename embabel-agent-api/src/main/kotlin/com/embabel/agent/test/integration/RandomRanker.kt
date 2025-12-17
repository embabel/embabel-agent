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
package com.embabel.agent.test.integration

import com.embabel.agent.api.ranking.Ranker
import com.embabel.agent.api.ranking.Ranking
import com.embabel.agent.api.ranking.Rankings
import com.embabel.common.core.types.Described
import com.embabel.common.core.types.Named
import kotlin.random.Random

/**
 * Identifies goal rankers used for test
 */
interface FakeRanker : Ranker

class RandomRanker : FakeRanker {
    private val random = Random(System.currentTimeMillis())

    override fun <T> rank(
        description: String,
        userInput: String,
        rankables: Collection<T>,
    ): Rankings<T> where T : Named, T : Described {

        return Rankings(rankables.map {
            Ranking(
                match = it,
                score = random.nextDouble(),
            )
        })
    }

}
