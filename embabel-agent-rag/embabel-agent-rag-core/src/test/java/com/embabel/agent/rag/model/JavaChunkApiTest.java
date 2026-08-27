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
package com.embabel.agent.rag.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaChunkApiTest {

    @Test
    void typedStructureIsUsableFromJava() {
        var structure = ChunkStructure.fromMetadata(Map.of(
                "root_document_id", "root-1",
                "container_section_id", "container-1",
                "sequence_number", 3));
        var chunk = Chunk.create("text", "container-1", Map.of("context", "user1"), "c-1", "text", structure);
        assertEquals("root-1", chunk.getStructure().getRootDocumentId());
        assertEquals(Integer.valueOf(3), chunk.getStructure().getSequenceNumber());
        assertEquals(3, chunk.getMetadata().get("sequence_number"));
        assertEquals("user1", chunk.getMetadata().get("context"));
    }

    @Test
    void legacyMetadataConstructionLiftsToTypedStructure() {
        var chunk = Chunk.create("text", "parent", Map.of("sequence_number", "3", "root_document_id", "root-1"));
        assertEquals(Integer.valueOf(3), chunk.getStructure().getSequenceNumber());
        assertEquals("root-1", chunk.getStructure().getRootDocumentId());
    }
}
