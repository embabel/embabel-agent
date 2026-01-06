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
package com.embabel.agent.api.annotation.support.state;

import com.embabel.agent.api.annotation.*;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.domain.library.HasContent;
import com.embabel.agent.prompt.persona.PersonaSpec;
import com.embabel.agent.prompt.persona.RoleGoalBackstorySpec;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.common.core.types.Timestamped;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

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

    public record ReviewedStory(
            Story story,
            String review,
            PersonaSpec reviewer
    ) implements HasContent, Timestamped {

        @Override
        @NonNull
        public Instant getTimestamp() {
            return Instant.now();
        }

        @Override
        @NonNull
        public String getContent() {
            return String.format("""
                            # Story
                            %s
                            
                            # Review
                            %s
                            
                            # Reviewer
                            %s, %s
                            """,
                    story.text(),
                    review,
                    reviewer.getName(),
                    getTimestamp().atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("EEEE, MMMM dd, yyyy"))
            ).trim();
        }
    }

    private final int storyWordCount;
    private final int reviewWordCount;
    // Counter for deterministic assessment: first call rejects, second accepts
    private static final AtomicInteger assessmentCount = new AtomicInteger(0);

    static void resetAssessmentCount() {
        assessmentCount.set(0);
    }

    WriteAndReviewAgent(
            @Value("${storyWordCount:100}") int storyWordCount,
            @Value("${reviewWordCount:100}") int reviewWordCount
    ) {
        this.storyWordCount = storyWordCount;
        this.reviewWordCount = reviewWordCount;
    }

    @State
    interface Stage {
    }

    @Action
    AssessStory craftStory(UserInput userInput, Ai ai) {
        var draft = ai
                .withDefaultLlm()
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
        // Because an @State class is returned,
        // it will be unwrapped and its effects and goals processed next
        // We can see that this one returns AssessStory which can
        // return Done or ReviseStory, and repeat the effect/goal
        // unrolling process
        return new AssessStory(userInput, draft);
    }

    @State
    record AssessStory(UserInput userInput, Story story) implements Stage {

        @Action
        HumanFeedback getFeedback() {
            return new HumanFeedback("whatever");
        }

        @Action
        Stage assess(HumanFeedback feedback, Ai ai) {
            // First assessment rejects (count=0), subsequent assessments accept
            var assessment = new Assessment(assessmentCount.getAndIncrement() > 0);
            if (assessment.acceptable) {
                return new Done(userInput, story);
            } else {
                return new ReviseStory(userInput, story, feedback);
            }
        }
    }

    @State
    record ReviseStory(UserInput userInput, Story story, HumanFeedback humanFeedback) implements Stage {

        @Action
        AssessStory reviseStory(Ai ai) {
            var draft = ai
                    // Higher temperature for more creative output
                    .withLlm(LlmOptions
                            .withAutoLlm() // You can also choose a specific model or role here
                            .withTemperature(.7)
                    )
                    .withPromptContributor(Personas.WRITER)
                    .createObject(String.format("""
                                    Revise a short story in %d words or less.
                                    The story should be engaging and imaginative.
                                    Use the user's input as inspiration if possible.
                                    If the user has provided a name, include it in the story.
                                    
                                    # User input
                                    %s
                                    
                                    # Previous story
                                    %s
                                    
                                    # Revision instructions
                                    %s
                                    """,
                            200,
                            userInput.getContent(),
                            story.text(),
                            humanFeedback.comments()
                    ).trim(), Story.class);
            return new AssessStory(userInput, draft);
        }
    }

    @State
    record Done(UserInput userInput, Story story) implements Stage {

        @AchievesGoal(
                description = "The story has been crafted and reviewed by a book reviewer",
                export = @Export(remote = true, name = "writeAndReviewStory"))
        @Action
        ReviewedStory reviewStory(Ai ai) {
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
                            story.text(),
                            userInput.getContent()
                    ).trim());

            return new ReviewedStory(
                    story,
                    review,
                    Personas.REVIEWER
            );
        }
    }


    record HumanFeedback(String comments) {
    }

    record Assessment(boolean acceptable) {
    }

}