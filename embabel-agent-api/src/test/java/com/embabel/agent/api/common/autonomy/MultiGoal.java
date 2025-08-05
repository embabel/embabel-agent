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
package com.embabel.agent.api.common.autonomy;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.WaitFor;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.domain.library.ResearchReport;

import java.util.Collections;

public class MultiGoal {
}

@Agent(description = "Research a topic")
class TopicResearchAgent {

    @AchievesGoal(description = "Researches a topic based on user input")
    @Action
    public ResearchReport researchTopic(UserInput userInput) {
        return new ResearchReport("We researched %s".formatted(userInput.getContent()), Collections.emptyList());
    }

}

class CodeResearchReport extends ResearchReport {

    public CodeResearchReport(String content) {
        super(content, Collections.emptyList());
    }

}

@Agent(description = "Research a codebase and report on it")
class CodeResearchAgent {

    @AchievesGoal(description = "Researches a topic based on user input")
    @Action
    public CodeResearchReport researchTopic(UserInput userInput) {
        return new CodeResearchReport("We researched code %s".formatted(userInput.getContent()));
    }

}

record DeckRequest(String title, int slideCount) {
}

record Deck(String title, String content) {
}

@Agent(description = "Build a slide deck based on user input and research")
class Decker {

    // What if multiple agents can achieve the first part of the goal?

    // TODO we could export this from an @PromptFor annotation, or do we want to make it explicit?
    @Action
    public DeckRequest askForDeckRequest() {
        return WaitFor.formSubmission(
                "Please provide details for the slide deck you want to build:",
                DeckRequest.class);
    }

    @AchievesGoal(description = "Builds a slide deck")
    @Action
    public Deck analyzeTopic(DeckRequest deckRequest, ResearchReport researchReport) {
        return new Deck("My title", "We built a deck from %s, informed by %s".formatted(
                deckRequest.title(), researchReport.getContent()));
    }
}

// Consider intent "Build me a slide deck about cane toads"
// "Build me a slide deck about the embabel software project"
