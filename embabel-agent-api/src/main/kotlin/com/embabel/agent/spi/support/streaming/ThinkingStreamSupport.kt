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
package com.embabel.agent.spi.support.streaming

import com.embabel.agent.spi.loop.streaming.LlmStreamChunk
import com.embabel.common.core.streaming.StreamingEvent
import com.embabel.common.core.thinking.ThinkingTags
import reactor.core.publisher.Flux

internal sealed interface ThinkingStreamItem {
    data class Thinking(val content: String) : ThinkingStreamItem

    data class Text(val content: String) : ThinkingStreamItem
}

/**
 * Turns provider chunks into ordered thinking/text items while buffering JSONL
 * text by line. The buffer is created per subscription so one Flux can be
 * safely reused by multiple callers.
 */
internal fun Flux<LlmStreamChunk>.toThinkingStreamItems(): Flux<ThinkingStreamItem> =
    Flux.defer {
        val lineBuffer = StringBuilder()

        fun emitLines(final: Boolean): List<ThinkingStreamItem.Text> {
            val lines = mutableListOf<ThinkingStreamItem.Text>()
            while (true) {
                val index = lineBuffer.indexOf("\n")
                if (index < 0) break
                val line = lineBuffer.substring(0, index).trim()
                if (line.isNotEmpty()) lines += ThinkingStreamItem.Text(line)
                lineBuffer.delete(0, index + 1)
            }
            if (final && lineBuffer.isNotEmpty()) {
                val line = lineBuffer.toString().trim()
                if (line.isNotEmpty()) lines += ThinkingStreamItem.Text(line)
                lineBuffer.clear()
            }
            return lines
        }

        this@toThinkingStreamItems.concatMap { chunk ->
            val items = buildList {
                chunk.thinkingContent
                    .filter { it.isNotBlank() }
                    .forEach { add(ThinkingStreamItem.Thinking(it)) }
                if (chunk.textContent.isNotEmpty()) {
                    lineBuffer.append(chunk.textContent)
                    addAll(emitLines(final = false))
                }
            }
            Flux.fromIterable(items)
        }.concatWith(
            Flux.defer { Flux.fromIterable(emitLines(final = true)) }
        )
    }

/**
 * Converts structured provider chunks to text thinking events. Provider
 * thinking is emitted before text from the same chunk, while tagged thinking
 * is extracted incrementally across arbitrary chunk boundaries.
 */
internal fun Flux<LlmStreamChunk>.toTaggedThinkingEvents(): Flux<StreamingEvent<String>> =
    Flux.defer {
        val parser = TaggedThinkingParser()
        this@toTaggedThinkingEvents.concatMap { chunk ->
            val events = buildList {
                chunk.thinkingContent
                    .filter { it.isNotBlank() }
                    .forEach { add(StreamingEvent.Thinking(it)) }
                if (chunk.textContent.isNotEmpty()) {
                    addAll(parser.accept(chunk.textContent))
                }
            }
            Flux.fromIterable(events)
        }.concatWith(
            Flux.defer { Flux.fromIterable(parser.finish()) }
        )
    }

private class TaggedThinkingParser {

    private data class Tag(
        val start: String,
        val end: String,
    )

    private data class StartMatch(
        val index: Int,
        val tag: Tag?,
        val legacy: Boolean,
    )

    private val tags = ThinkingTags.TAG_DEFINITIONS
        .filterKeys { it != "legacy_prefix" && it != "no_prefix" }
        .values
        .map { Tag(it.first, it.second) }

    private val legacyPrefix = ThinkingTags.TAG_DEFINITIONS["legacy_prefix"]?.first.orEmpty()
    private val buffer = StringBuilder()
    private var activeTag: Tag? = null
    private var atLineStart = true

    fun accept(text: String): List<StreamingEvent<String>> {
        buffer.append(text)
        val events = mutableListOf<StreamingEvent<String>>()

        while (buffer.isNotEmpty()) {
            val tag = activeTag
            if (tag != null) {
                val endIndex = buffer.indexOf(tag.end)
                if (endIndex < 0) break
                val thinking = buffer.substring(0, endIndex).trim()
                if (thinking.isNotEmpty()) events += StreamingEvent.Thinking(thinking)
                buffer.delete(0, endIndex + tag.end.length)
                activeTag = null
                atLineStart = false
                continue
            }

            if (atLineStart && legacyPrefix.isNotEmpty() && buffer.startsWith(legacyPrefix)) {
                val newlineIndex = buffer.indexOf("\n")
                if (newlineIndex < 0) break
                val thinking = buffer.substring(legacyPrefix.length, newlineIndex).trim()
                if (thinking.isNotEmpty()) events += StreamingEvent.Thinking(thinking)
                buffer.delete(0, newlineIndex + 1)
                atLineStart = true
                continue
            }

            val match = findStart()
            if (match != null) {
                if (match.index > 0) {
                    emitText(events, buffer.substring(0, match.index))
                    buffer.delete(0, match.index)
                    continue
                }
                if (match.legacy) {
                    // The prefix is handled above once a complete line is available.
                    break
                }
                activeTag = match.tag
                buffer.delete(0, match.tag!!.start.length)
                atLineStart = false
                continue
            }

            val heldSuffixLength = longestPartialStartSuffix()
            val emitLength = buffer.length - heldSuffixLength
            if (emitLength > 0) {
                emitText(events, buffer.substring(0, emitLength))
                buffer.delete(0, emitLength)
            }
            break
        }

        return events
    }

    fun finish(): List<StreamingEvent<String>> {
        val events = mutableListOf<StreamingEvent<String>>()
        val tag = activeTag
        if (tag != null) {
            emitText(events, tag.start + buffer.toString())
        } else if (atLineStart && legacyPrefix.isNotEmpty() && buffer.startsWith(legacyPrefix)) {
            val thinking = buffer.substring(legacyPrefix.length).trim()
            if (thinking.isNotEmpty()) events += StreamingEvent.Thinking(thinking)
        } else {
            emitText(events, buffer.toString())
        }
        buffer.clear()
        activeTag = null
        atLineStart = true
        return events
    }

    private fun findStart(): StartMatch? {
        val tagMatch = tags
            .mapNotNull { tag -> buffer.indexOf(tag.start).takeIf { it >= 0 }?.let { StartMatch(it, tag, false) } }
            .minWithOrNull(compareBy<StartMatch> { it.index }.thenByDescending { it.tag?.start?.length ?: 0 })
        val legacyMatch = if (legacyPrefix.isNotEmpty()) {
            findLegacyStart()?.let { StartMatch(it, null, true) }
        } else {
            null
        }
        return listOfNotNull(tagMatch, legacyMatch)
            .minByOrNull { it.index }
    }

    private fun findLegacyStart(): Int? {
        var fromIndex = 0
        while (fromIndex < buffer.length) {
            val index = buffer.indexOf(legacyPrefix, fromIndex)
            if (index < 0) return null
            if ((index == 0 && atLineStart) || (index > 0 && buffer[index - 1] == '\n')) return index
            fromIndex = index + 1
        }
        return null
    }

    private fun longestPartialStartSuffix(): Int {
        val tagSuffixLength = tags.map { it.start }.maxOfOrNull { start ->
            (1 until start.length)
                .filter { length -> buffer.endsWith(start.substring(0, length)) }
                .maxOrNull() ?: 0
        } ?: 0
        return maxOf(tagSuffixLength, longestPartialLegacySuffix())
    }

    private fun longestPartialLegacySuffix(): Int {
        if (legacyPrefix.isEmpty()) return 0
        return (1 until legacyPrefix.length)
            .filter { length ->
                if (!buffer.endsWith(legacyPrefix.substring(0, length))) return@filter false
                val startIndex = buffer.length - length
                (startIndex == 0 && atLineStart) || (startIndex > 0 && buffer[startIndex - 1] == '\n')
            }
            .maxOrNull() ?: 0
    }

    private fun emitText(events: MutableList<StreamingEvent<String>>, text: String) {
        if (text.isNotEmpty()) {
            events += StreamingEvent.Object(text)
            atLineStart = text.endsWith("\n")
        }
    }
}
