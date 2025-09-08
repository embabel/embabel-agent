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

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * @param chunkNodeName the name of the node representing a chunk in the knowledge graph
 * @param entityNodeName the name of a node representing an entity in the knowledge graph
 * @param packages the packages to scan for Neo4j OGM entities. Defaults to none
 */
@ConfigurationProperties(prefix = "embabel.agent.rag.neo")
data class NeoRagServiceProperties(
    val uri: String = "bolt://localhost:7687",
    val username: String = "neo4j",
    internal val password: String = "brahmsian",

    val chunkNodeName: String = "Chunk",
    val entityNodeName: String = "Entity",
    val name: String = "OgmRagService",
    val description: String = "RAG service using Neo4j OGM for querying and embedding",
    val contentElementIndex: String = "embabel-content-index",
    val entityIndex: String = "embabel-entity-index",

    // Empty packages causes a strange failure within Neo4j OGM
    val packages: List<String> = listOf("not.a.real.package"),
)
