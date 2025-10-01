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
package com.embabel.agent.rag.neo.ogm

import com.embabel.agent.rag.ingestion.ContentChunker
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * @param chunkNodeName the name of the node representing a chunk in the knowledge graph
 * @param entityNodeName the name of a node representing an entity in the knowledge graph
 * @param ogmPackages the packages to scan for Neo4j OGM entities. Defaults to none
 */
@ConfigurationProperties(prefix = "embabel.agent.rag.neo")
class NeoRagServiceProperties : ContentChunker.Config {
    var uri: String = "bolt://localhost:7687"
    var username: String = "neo4j"
    internal var password: String = ""

    var chunkNodeName: String = "Chunk"
    var entityNodeName: String = "Entity"
    var name: String = "OgmRagService"
    var description: String = "RAG service using Neo4j OGM for querying and embedding"
    var contentElementIndex: String = "embabel-content-index"
    var entityIndex: String = "embabel-entity-index"
    var contentElementFullTextIndex: String = "embabel-content-fulltext-index"
    var entityFullTextIndex: String = "embabel-entity-fulltext-index"

    // Empty packages causes a strange failure within Neo4j OGM
    var ogmPackages: List<String> = listOf("not.a.real.package")
    override var maxChunkSize: Int = 1500
    override var overlapSize: Int = 200
}
