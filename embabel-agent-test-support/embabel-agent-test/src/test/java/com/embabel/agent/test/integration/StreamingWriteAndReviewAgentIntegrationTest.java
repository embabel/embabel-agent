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
package com.embabel.agent.test.integration;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.test.integration.StreamingWriteAndReviewAgent.StreamingReviewedStory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static com.embabel.agent.test.integration.StreamingWriteAndReviewAgent.StreamingStory;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Use framework superclass to test the complete workflow of writing and reviewing a story.
 * This will run under Spring Boot against an AgentPlatform instance
 * that has loaded all our agents.
 */
class StreamingWriteAndReviewAgentIntegrationTest extends EmbabelMockitoIntegrationTest {

    @BeforeEach
    void enableStreaming(){
        supportsStreaming(true);
    }

    @Test
    void shouldExecuteCompleteWorkflow() {
        var input = new UserInput("Write about artificial intelligence");

        var story = new StreamingStory("AI will transform our world...");
        var reviewedStory = new StreamingReviewedStory(story, "Excellent exploration of AI themes.", Personas.REVIEWER);

        whenCreateObjectStream(s -> s.contains("Craft a short story"), StreamingStory.class)
                .thenReturn(Flux.just(story));

        // The second call uses generateText
        whenGenerateStream(s -> s.contains("You will be given a short story to review"))
                .thenReturn(Flux.just(reviewedStory.review()));

        var invocation = AgentInvocation.create(agentPlatform, StreamingReviewedStory.class);
        var reviewedStoryResult = invocation.invoke(input);

        assertNotNull(reviewedStoryResult);
        assertTrue(reviewedStoryResult.story().text().contains(story.text()),
                "Expected story content to be present: " + reviewedStoryResult.story().text());
        assertEquals(reviewedStory, reviewedStoryResult,
                "Expected review to match: " + reviewedStoryResult);

        verifyCreateObjectStreamMatching(prompt -> prompt.contains("Craft a short story"), StreamingStory.class,
                llm -> llm.getLlm().getTemperature() != null && llm.getLlm().getTemperature() == 0.9 && llm.getToolGroups().isEmpty());
        verifyGenerateStreamMatching(prompt -> prompt.contains("You will be given a short story to review"));
        verifyNoMoreInteractions();
    }
}