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
package com.embabel.agent.rag.tools;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.rag.model.Chunk;
import com.embabel.agent.rag.model.Retrievable;
import com.embabel.agent.rag.service.RetrievableResultsFormatter;
import com.embabel.agent.rag.service.TextSearch;
import com.embabel.agent.rag.service.VectorSearch;
import com.embabel.common.core.types.SimilarityResult;
import com.embabel.common.core.types.SimpleSimilaritySearchResult;
import com.embabel.common.core.types.TextSimilaritySearchRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaToolishRagFormatterTest {

    private static final RetrievableResultsFormatter CUSTOM = results -> "CUSTOM " + results.getResults().stream()
            .map(result -> result.getMatch().getId())
            .collect(Collectors.joining(","));

    private final ToolishRag rag = new ToolishRag("fmt", "Formatter RAG", new StubSearch()).withFormatter(CUSTOM);

    @Test
    void vectorSearchToolUsesConfiguredFormatter() {
        assertEquals("CUSTOM 42", callTool("fmt_vectorSearch"));
    }

    @Test
    void textSearchToolUsesConfiguredFormatter() {
        assertEquals("CUSTOM 42", callTool("fmt_textSearch"));
    }

    private String callTool(String name) {
        var tool = rag.tools().stream()
                .filter(t -> t.getDefinition().getName().equals(name))
                .findFirst()
                .orElseThrow();
        var result = tool.call("{\"query\": \"q\", \"topK\": 5, \"threshold\": 0.0}");
        return ((Tool.Result.Text) result).getContent();
    }

    private static final class StubSearch implements VectorSearch, TextSearch {

        private static final List<SimilarityResult<Chunk>> RESULTS = List.of(
                new SimpleSimilaritySearchResult<>(Chunk.create("Some text", "parent", Map.of(), "42"), 0.6)
        );

        @Override
        public boolean supportsType(String type) {
            return true;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public <T extends Retrievable> List<SimilarityResult<T>> vectorSearch(TextSimilaritySearchRequest request, Class<T> clazz) {
            return (List) RESULTS;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public <T extends Retrievable> List<SimilarityResult<T>> textSearch(TextSimilaritySearchRequest request, Class<T> clazz) {
            return (List) RESULTS;
        }
    }
}
