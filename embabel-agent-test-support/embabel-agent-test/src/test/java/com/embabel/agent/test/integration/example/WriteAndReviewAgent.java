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
package com.embabel.agent.test.integration.example;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Export;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.prompt.persona.PersonaSpec;
import com.embabel.agent.prompt.persona.RoleGoalBackstorySpec;
import com.embabel.common.ai.model.LlmOptions;
import org.springframework.beans.factory.annotation.Value;

abstract class Personas {
    static final RoleGoalBackstorySpec WRITER = RoleGoalBackstorySpec
            .withRole("Creative Storyteller")
            .andGoal("Write engaging and imaginative stories")
            .andBackstory("Has a PhD in French literature; used to work in a circus");

    static final PersonaSpec REVIEWER = PersonaSpec.create(
            "Media Book Review",
            "New York Times Book Reviewer",
            "Professional and insightful",
            "Help guide readers toward good stories"
    );
}

@Agent(description = "Generate a story based on user input and review it")
public class WriteAndReviewAgent {

    public record Story(String text) {
    }

    public record Draft(UserInput userInput, Story story) {
    }

    public record ReviewedStory(
            Story story,
            String review,
            PersonaSpec reviewer
    ) {
    }

    private final int storyWordCount;

    WriteAndReviewAgent(
            @Value("${storyWordCount:100}") int storyWordCount
    ) {
        this.storyWordCount = storyWordCount;
    }

    @Action
    Draft craftStory(UserInput userInput, Ai ai) {
        Story story = ai
                .withLlm(LlmOptions
                        .withDefaultLlm()
                        .withTemperature(0.9))
                .withPromptContributor(Personas.WRITER)
                .createObject(String.format("""
                                Craft a short story in %d words or less.
                                The story should be engaging and imaginative.
                                Use the user's input as inspiration if possible.
                                If the user has provided a name, include it in the story.
                                
                                # User input
                                %s
                                """,
                        storyWordCount,
                        userInput.getContent()
                ).trim(), Story.class);

        return new Draft(userInput, story);
    }

    @AchievesGoal(
            description = "The story has been crafted and reviewed by a book reviewer",
            export = @Export(remote = true, name = "writeAndReviewStory"))
    @Action
    ReviewedStory reviewStory(Draft draft, Ai ai) {
        var review = ai
                .withAutoLlm()
                .withPromptContributor(Personas.REVIEWER)
                .generateText(String.format("""
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
                ).trim());

        return new ReviewedStory(
                draft.story,
                review,
                Personas.REVIEWER
        );
    }
}




