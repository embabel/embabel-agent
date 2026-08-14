package com.embabel.common.util

import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Immutable wrapper around the Jackson [tools.jackson.databind.ObjectMapper] used by the Embabel platform.
 *
 * This type is exposed so that the underlying [tools.jackson.databind.ObjectMapper] is not registered directly in the application
 * context. Consumers should call [get] to obtain the configured [tools.jackson.databind.ObjectMapper].
 */
class EmbabelObjectMapperHolder(private val objectMapper: ObjectMapper) {
    fun get(): ObjectMapper = objectMapper

    override fun toString(): String = "EmbabelObjectMapper($objectMapper)"

    companion object {
        @JvmStatic
        fun createDefault(): EmbabelObjectMapperHolder = EmbabelObjectMapperHolder(jacksonObjectMapper())
    }
}