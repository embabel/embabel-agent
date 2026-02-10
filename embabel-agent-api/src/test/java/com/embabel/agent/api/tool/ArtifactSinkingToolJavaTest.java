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
package com.embabel.agent.api.tool;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java interoperability tests for artifact sinking tool decorators.
 * These tests verify that Tool.sinkArtifacts() and Tool.publishToBlackboard()
 * can be called from Java with clean syntax.
 */
class ArtifactSinkingToolJavaTest {

    @Nested
    class SinkArtifactsTests {

        @Test
        void sinkArtifactsWrapsToolCorrectly() {
            Tool delegate = Tool.create(
                    "my_tool",
                    "A simple tool",
                    input -> Tool.Result.withArtifact("result", "artifact")
            );
            var sink = new ListSink();

            var wrapped = Tool.sinkArtifacts(delegate, String.class, sink);

            assertNotNull(wrapped);
            assertEquals("my_tool", wrapped.getDefinition().getName());
            assertInstanceOf(ArtifactSinkingTool.class, wrapped);
        }

        @Test
        void sinkArtifactsCapturesMatchingType() {
            Tool delegate = Tool.create(
                    "capture_tool",
                    "Captures strings",
                    input -> Tool.Result.withArtifact("content", "captured value")
            );
            var sink = new ListSink();

            var wrapped = Tool.sinkArtifacts(delegate, String.class, sink);
            wrapped.call("{}");

            assertEquals(1, sink.items().size());
            assertEquals("captured value", sink.items().get(0));
        }

        @Test
        void sinkArtifactsWithFilterAndTransform() {
            Tool delegate = Tool.create(
                    "filter_tool",
                    "Filters and transforms",
                    input -> Tool.Result.withArtifact("content", "hello")
            );
            var sink = new ListSink();

            var wrapped = Tool.sinkArtifacts(
                    delegate,
                    String.class,
                    sink,
                    s -> s.length() > 3,
                    String::toUpperCase
            );
            wrapped.call("{}");

            assertEquals(1, sink.items().size());
            assertEquals("HELLO", sink.items().get(0));
        }

        @Test
        void sinkArtifactsFilterRejectsNonMatching() {
            Tool delegate = Tool.create(
                    "reject_tool",
                    "Rejects short strings",
                    input -> Tool.Result.withArtifact("content", "hi")
            );
            var sink = new ListSink();

            var wrapped = Tool.sinkArtifacts(
                    delegate,
                    String.class,
                    sink,
                    s -> s.length() > 3,
                    s -> s
            );
            wrapped.call("{}");

            assertTrue(sink.items().isEmpty());
        }
    }

    @Nested
    class PublishToBlackboardTests {

        @Test
        void publishToBlackboardWrapsToolCorrectly() {
            Tool delegate = Tool.create(
                    "bb_tool",
                    "Publishes to blackboard",
                    input -> Tool.Result.withArtifact("result", "artifact")
            );

            var wrapped = Tool.publishToBlackboard(delegate);

            assertNotNull(wrapped);
            assertEquals("bb_tool", wrapped.getDefinition().getName());
            assertInstanceOf(ArtifactSinkingTool.class, wrapped);
        }

        @Test
        void publishToBlackboardWithTypeParam() {
            Tool delegate = Tool.create(
                    "typed_bb_tool",
                    "Publishes typed to blackboard",
                    input -> Tool.Result.withArtifact("result", "artifact")
            );

            var wrapped = Tool.publishToBlackboard(delegate, String.class);

            assertNotNull(wrapped);
            assertEquals("typed_bb_tool", wrapped.getDefinition().getName());
            assertInstanceOf(ArtifactSinkingTool.class, wrapped);
        }

        @Test
        void publishToBlackboardWithFilterAndTransform() {
            Tool delegate = Tool.create(
                    "filtered_bb_tool",
                    "Filters before publishing",
                    input -> Tool.Result.withArtifact("result", "accepted")
            );

            var wrapped = Tool.publishToBlackboard(
                    delegate,
                    String.class,
                    s -> s.startsWith("acc"),
                    String::toUpperCase
            );

            assertNotNull(wrapped);
            assertEquals("filtered_bb_tool", wrapped.getDefinition().getName());
            assertInstanceOf(ArtifactSinkingTool.class, wrapped);
        }
    }
}
