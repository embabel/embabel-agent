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
package com.embabel.agent.rag.ogm

import com.embabel.agent.rag.neo.ogm.OgmCypherSearch
import com.embabel.test.NeoIntegrationTest
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired


@DisabledIfEnvironmentVariable(named = "SKIP_TESTCONTAINER_TESTS", matches = "true")
@NeoIntegrationTest
class OgmCypherSearchTest(
    @param:Autowired private val ogmCypherSearch: OgmCypherSearch,
) {

     @Test
    fun should_query() {
        val query = "match (n) return n limit 100"
        val params: Map<String, *> = emptyMap<String, Any>()
        val result = ogmCypherSearch.query("purpose", query, params, null)
        println("Got result: $result")
        assertNotNull(result)
    }
}
