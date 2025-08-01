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
package com.embabel.agent.eval.assert

import com.embabel.agent.eval.client.AgentChatClient
import com.embabel.agent.eval.config.ScriptEvaluationService
import com.embabel.agent.eval.support.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Service

@Service
class AssertionEvaluator(
    private val neo4jClient: Neo4jClient,
    private val agentChatClient: AgentChatClient,
    private val scriptEvaluationService: ScriptEvaluationService,
) : SetupRunner {

    private val logger: Logger = LoggerFactory.getLogger(AssertionEvaluator::class.java)

    override fun execute(setup: Setup) {
        when (setup) {
            is CypherSetup -> {
                logger.info("Running Cypher setup query: {}", setup.cypher)
                neo4jClient.query(setup.cypher).run()
            }

            else -> error("Unsupported setup type: ${setup::class.simpleName}")
        }
    }

    fun evaluate(
        evaluationInProgress: EvaluationInProgress,
        assertion: Assertion,
    ): Score {
        return when (assertion) {
            is ObjectContextAssertion -> {

                // Get the object context for the current session through a call to the agent server
                val objectContext = agentChatClient.getObjectContext(evaluationInProgress.sessionId)
                val result = scriptEvaluationService.evaluateExpression<Boolean>(
                    assertion.expression,
                    mapOf(
                        "objectContext" to objectContext,
                        "evaluation" to evaluationInProgress,
                    ),
                )
                val mark = if (result == true) "✅" else "❌"
                logger.info("{} Expression assertion={}: {}", mark, result, assertion.expression)

                Score(
                    score = if (result == true) 1.0 else 0.0,
                    scored = assertion.name,
                )
            }

            is CypherAssertion -> {
                try {
                    val result = neo4jClient
                        .query(assertion.cypher)
                        .fetchAs(String::class.java)
                        .one()
                        .orElseThrow()
                    val bool = result == "" + assertion.expected
                    val mark = if (bool) "✅" else "❌"
                    logger.info("{} Cypher assertion={}: {} from {}", mark, bool, result, assertion.cypher)
                    Score(
                        score = if (bool) 1.0 else 0.0,
                        scored = assertion.name,
                    )
                } catch (e: Exception) {
                    Score(
                        score = 0.0,
                        scored = assertion.name,
                    )
                }
            }

            else -> error("Unsupported assertion type: ${assertion::class.simpleName}")
        }
    }
}
