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
package com.embabel.agent.rag.lucene

import org.apache.lucene.codecs.FilterCodec
import org.apache.lucene.codecs.KnnVectorsFormat
import org.apache.lucene.codecs.KnnVectorsReader
import org.apache.lucene.codecs.KnnVectorsWriter
import org.apache.lucene.codecs.lucene99.Lucene99Codec
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat
import org.apache.lucene.index.SegmentReadState
import org.apache.lucene.index.SegmentWriteState

/**
 * Custom codec that supports higher vector dimensions than the default 1024.
 * This enables use of OpenAI embeddings (1536 dimensions) and other large embedding models.
 *
 * Uses FilterCodec to wrap Lucene99Codec and override only the KnnVectorsFormat
 * with a delegating format that reports a higher max dimension limit.
 *
 * This codec is registered via SPI in META-INF/services/org.apache.lucene.codecs.Codec
 * so that Lucene can find it when reading indexes from disk.
 */
class HighDimensionVectorCodec @JvmOverloads constructor(
    private val maxDimensions: Int = DEFAULT_MAX_DIMENSIONS,
) : FilterCodec(CODEC_NAME, Lucene99Codec()) {

    private val delegateFormat = Lucene99HnswVectorsFormat()

    override fun knnVectorsFormat(): KnnVectorsFormat = object : KnnVectorsFormat(delegateFormat.name) {
        override fun getMaxDimensions(fieldName: String): Int = maxDimensions

        override fun fieldsWriter(state: SegmentWriteState): KnnVectorsWriter =
            delegateFormat.fieldsWriter(state)

        override fun fieldsReader(state: SegmentReadState): KnnVectorsReader =
            delegateFormat.fieldsReader(state)
    }

    companion object {
        const val CODEC_NAME = "HighDimensionVectorCodec"
        const val DEFAULT_MAX_DIMENSIONS = 4096
    }
}
