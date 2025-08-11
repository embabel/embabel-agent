package com.embabel.agent.domain.library

/**
 * Summary of context for an operation, such as a document or a conversation.
 */
data class Summary(
    val summary: String,
) : HasContent {

    override val content = summary
}