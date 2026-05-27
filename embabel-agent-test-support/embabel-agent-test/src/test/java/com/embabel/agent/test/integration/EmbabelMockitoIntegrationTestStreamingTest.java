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

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Export;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.api.streaming.StreamingPromptRunnerBuilder;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.prompt.persona.PersonaSpec;
import com.embabel.common.ai.model.LlmOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test the framework superclass for use in test of an agent using streaming LLM-calls.
 */
class EmbabelMockitoIntegrationTestStreamingTest extends EmbabelMockitoIntegrationTest {

    @BeforeEach
    void enableStreaming(){
        supportsStreaming(true);
    }

    @Test
    void shouldExecuteCompleteWorkflow() {
        var input = new UserInput("Write about artificial intelligence");

        var story = new StreamingTestAgent.Story("AI will transform our world...");
        var reviewedStory = new StreamingTestAgent.ReviewedStory(story, "Excellent exploration of AI themes.", EmbabelMockitoIntegrationTestApplication.Personas.REVIEWER);

        whenCreateObjectStream(s -> s.contains("Craft a short story"), StreamingTestAgent.Story.class)
                .thenReturn(Flux.just(story));

        // The second call uses generateText
        whenGenerateStream(s -> s.contains("You will be given a short story to review"))
                .thenReturn(Flux.just(reviewedStory.review()));

        var invocation = AgentInvocation.create(agentPlatform, StreamingTestAgent.ReviewedStory.class);
        var reviewedStoryResult = invocation.invoke(input);

        assertNotNull(reviewedStoryResult);
        assertTrue(reviewedStoryResult.story().text().contains(story.text()),
                "Expected story content to be present: " + reviewedStoryResult.story().text());
        assertEquals(reviewedStory, reviewedStoryResult,
                "Expected review to match: " + reviewedStoryResult);

        verifyCreateObjectStreamMatching(prompt -> prompt.contains("Craft a short story"), StreamingTestAgent.Story.class,
                llm -> llm.getLlm().getTemperature() != null && llm.getLlm().getTemperature() == 0.9 && llm.getToolGroups().isEmpty());
        verifyGenerateStreamMatching(prompt -> prompt.contains("You will be given a short story to review"));
        verifyNoMoreInteractions();
    }

    @Agent(description = "Streaming agent, created for testing purposes only")
    private static class StreamingTestAgent {

        private record Story(String text) {
        }

        private record StreamingDraft(UserInput userInput, Story story) {
        }

        private record ReviewedStory(
                Story story,
                String review,
                PersonaSpec reviewer
        ) {
        }

        @Action
        StreamingDraft craftStory(UserInput userInput, Ai ai) {
            PromptRunner runner = ai
                    .withLlm(LlmOptions
                            .withDefaultLlm()
                            .withTemperature(0.9))
                    .withPromptContributor(EmbabelMockitoIntegrationTestApplication.Personas.WRITER);

            Story story = new StreamingPromptRunnerBuilder(runner)
                    .streaming()
                    .withPrompt(String.format("""
                                    Craft a short story in 100 words or less.
                                    The story should be engaging and imaginative.
                                    Use the user's input as inspiration if possible.
                                    If the user has provided a name, include it in the story.
                                    
                                    # User input
                                    %s
                                    """,
                            userInput.getContent()
                    ).trim())
                    .createObjectStream(Story.class)
                    .blockLast();

            return new StreamingDraft(userInput, story);
        }

        @AchievesGoal(
                description = "The story has been crafted and reviewed by a book reviewer",
                export = @Export(remote = true, name = "writeAndReviewStory"))
        @Action
        ReviewedStory reviewStory(StreamingDraft draft, Ai ai) {
            PromptRunner runner = ai
                    .withAutoLlm()
                    .withPromptContributor(EmbabelMockitoIntegrationTestApplication.Personas.REVIEWER);
            var review = new StreamingPromptRunnerBuilder(runner)
                    .streaming()
                    .withPrompt(String.format("""
                                    You will be given a short story to review.
                                    Review it in %d words or less.
                                    Consider whether or not the story is engaging, imaginative, and well-written.
                                    Also consider whether the story is appropriate given the original user input.
                                    
                                    # Story
                                    %s
                                    
                                    # User input that inspired the story
                                    %s
                                    """,
                            200,
                            draft.story().text(),
                            draft.userInput().getContent()
                    ).trim())
                    .generateStream()
                    .collectList()
                    .block();

            return new ReviewedStory(
                    draft.story,
                    String.join("", Objects.requireNonNull(review)),
                    EmbabelMockitoIntegrationTestApplication.Personas.REVIEWER
            );
        }
    }
}