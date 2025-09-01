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
package com.embabel.agent.experimental.domain.persistence

import com.embabel.agent.api.annotation.waitFor
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.core.hitl.ConfirmationRequest
import com.embabel.agent.experimental.domain.persistence.support.SpringDataRepositoryNaturalLanguageRepository
import com.embabel.common.ai.model.LlmOptions
import org.slf4j.LoggerFactory
import org.springframework.data.repository.CrudRepository

/**
 * Convenient function to call from actions.
 */
inline fun <reified T : Any, ID> CrudRepository<T, ID>.findOneFromContent(
    content: String,
    noinline idGetter: (T) -> ID?,
    context: OperationContext,
    llm: LlmOptions = LlmOptions(),
    noinline confirmOne: (match: EntityMatch<T>) -> ConfirmationRequest<T>? = { null },
): T? = NaturalLanguageRepositoryUtils.findOneFromContent(
    content = content,
    entityType = T::class.java,
    idGetter = idGetter,
    context = context,
    repository = this,
    llm = llm,
    confirmOne = confirmOne,
)


object NaturalLanguageRepositoryUtils {

    private val logger = LoggerFactory.getLogger(NaturalLanguageRepositoryUtils::class.java)

    @JvmStatic
    @JvmOverloads
    fun <T : Any, ID> findOneFromContent(
        content: String,
        entityType: Class<T>,
        idGetter: (T) -> ID?,
        context: OperationContext,
        repository: CrudRepository<T, ID>,
        llm: LlmOptions = LlmOptions(),
        confirmOne: (match: EntityMatch<T>) -> ConfirmationRequest<T>? = { null },
    ): T? {
        val nlRepository = SpringDataRepositoryNaturalLanguageRepository(
            repository = repository,
            entityType = entityType,
            idGetter = idGetter,
            context = context,
            llm = llm,
        ).find(
            FindEntitiesRequest(content = content),
        )
        // TODO present a choices form
        return when {
            nlRepository.matches.size == 1 -> {
                val match = nlRepository.matches.single()
                val confirmationRequest = confirmOne(match)
                if (confirmationRequest != null) {
                    waitFor(confirmationRequest)
                } else {
                    logger.info("Found one movie buff: {}", nlRepository.matches.single().match)
                    nlRepository.matches.single().match
                }
            }

            else -> {
                logger.info("Found {} movie buffs", nlRepository.matches.size)
                null
            }
        }
    }
}
