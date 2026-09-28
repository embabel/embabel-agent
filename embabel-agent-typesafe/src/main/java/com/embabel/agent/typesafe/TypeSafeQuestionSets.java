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
package com.embabel.agent.typesafe;

import com.embabel.common.ai.classification.Category;
import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.classification.FailureReason;
import com.embabel.common.ai.classification.ModelProvenance;
import com.embabel.common.ai.decision.ChoiceQuestionSpec;
import com.embabel.common.ai.decision.DecisionResponse;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.LevelProbability;
import com.embabel.common.ai.decision.PropositionQuestionSpec;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.decision.RatingLevel;
import com.embabel.common.ai.decision.RatingQuestionSpec;
import com.embabel.common.ai.decision.RatingResult;
import com.embabel.common.ai.decision.RatingScore;
import com.embabel.common.ai.decision.RatingStatistic;
import com.embabel.common.ai.decision.spi.DecisionResponseAssembler;
import com.embabel.common.ai.decision.spi.DecisionResponseAssembler.Anomaly;

import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.Question;
import org.springaicommunity.typesafe.question.Score;
import org.springaicommunity.typesafe.response.Answer;
import org.springaicommunity.typesafe.response.ChoiceAnswer;
import org.springaicommunity.typesafe.response.NoulAnswer;
import org.springaicommunity.typesafe.response.ScoreAnswer;
import org.springaicommunity.typesafe.response.SystemOneResponse;
import org.springaicommunity.typesafe.response.UnknownAnswer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps a decision spec to TypeSafe questions and a TypeSafe response back to a decision response.
 *
 * <p>Questions are keyed {@code q1..qN} in spec order, so caller question names never reach the
 * provider. Every answer is checked here and handed to a {@link DecisionResponseAssembler}, which
 * applies the answer rules and logs anomalies. Nothing in this class throws with response data in
 * a message, because response data can echo the decision input.
 */
final class TypeSafeQuestionSets {

    private static final double UNDECIDED = 0.5d;
    private static final double DISTRIBUTION_TOLERANCE = 1.0e-6d;
    private static final ClassificationResult CHOICE_PLACEHOLDER =
            new ClassificationResult.Failure(FailureReason.INVALID_RESPONSE);
    private static final RatingResult RATING_PLACEHOLDER =
            new RatingResult.Failure(FailureReason.INVALID_RESPONSE);
    private static final PropositionResult PROPOSITION_PLACEHOLDER =
            new PropositionResult.Failure(FailureReason.INVALID_RESPONSE);

    private TypeSafeQuestionSets() {}

    /** Native questions keyed q1..qN in spec order. */
    static Map<String, Question> questions(DecisionSpec spec) {
        var questions = new LinkedHashMap<String, Question>();
        var specQuestions = spec.getQuestions();
        for (int i = 0; i < specQuestions.size(); i++) {
            questions.put(key(i), question(specQuestions.get(i)));
        }
        return Collections.unmodifiableMap(questions);
    }

    /** Maps a validated SDK response onto the spec. */
    static DecisionResponse response(
            DecisionSpec spec,
            SystemOneResponse response,
            ModelProvenance provenance,
            String serviceName) {
        var byKey = new LinkedHashMap<String, com.embabel.common.ai.decision.Question<?>>();
        var specQuestions = spec.getQuestions();
        for (int i = 0; i < specQuestions.size(); i++) {
            byKey.put(key(i), specQuestions.get(i));
        }
        var assembler = DecisionResponseAssembler.forSpec(spec, serviceName);
        for (Map.Entry<String, Answer> entry : response.answers().entrySet()) {
            var question = entry.getKey() == null ? null : byKey.get(entry.getKey());
            if (question == null) {
                assembler.unexpected();
            } else {
                add(assembler, question, entry.getValue(), provenance);
            }
        }
        return assembler.build();
    }

    /**
     * Returns the question key for a position in the spec's question list.
     *
     * @param index the question's position, zero based
     * @return the key, such as q1 for index 0
     */
    private static String key(int index) {
        return "q" + (index + 1);
    }

    /**
     * Builds the native question that mirrors one spec question.
     *
     * @param question the spec question to mirror
     * @return the native question for the provider call
     */
    private static Question question(com.embabel.common.ai.decision.Question<?> question) {
        return switch (question) {
            case PropositionQuestionSpec proposition -> Noul.of(proposition.getInstructions());
            case ChoiceQuestionSpec choice -> {
                var builder = Choice.builder().instructions(choice.getInstructions());
                for (Category option : choice.getOptions()) {
                    builder.option(option.getId(), option.getDescription());
                }
                yield builder.build();
            }
            case RatingQuestionSpec rating -> {
                var builder = Score.builder().instructions(rating.getInstructions());
                for (RatingLevel level : rating.getLevels()) {
                    // The SDK rejects a blank level description, and a level's id also names it.
                    builder.level(level.getDescription().isBlank() ? level.getId() : level.getDescription());
                }
                yield builder.build();
            }
        };
    }

    /**
     * An answer of another kind goes to the assembler under its own kind, so it records WRONG_KIND.
     * The placeholder outcome is never placed in the response.
     *
     * @param assembler collects the outcome
     * @param question the question the answer maps to
     * @param answer the native answer for that question
     * @param provenance the model that produced the answer
     */
    private static void add(
            DecisionResponseAssembler assembler,
            com.embabel.common.ai.decision.Question<?> question,
            Answer answer,
            ModelProvenance provenance) {
        var name = question.getName();
        switch (answer) {
            case NoulAnswer noul when question instanceof PropositionQuestionSpec ->
                    proposition(assembler, name, noul, provenance);
            case ChoiceAnswer choice when question instanceof ChoiceQuestionSpec spec ->
                    choice(assembler, spec, choice, provenance);
            case ScoreAnswer score when question instanceof RatingQuestionSpec spec ->
                    rating(assembler, spec, score, provenance);
            case NoulAnswer ignored -> assembler.proposition(name, PROPOSITION_PLACEHOLDER);
            case ChoiceAnswer ignored -> assembler.choice(name, CHOICE_PLACEHOLDER);
            case ScoreAnswer ignored -> assembler.rating(name, RATING_PLACEHOLDER);
            case UnknownAnswer ignored -> assembler.unreadable(name);
            case null -> assembler.unreadable(name);
        }
    }

    /**
     * A value of exactly 0.5 is undecided, as in assess.
     *
     * @param assembler collects the outcome
     * @param name the question name
     * @param noul the native proposition answer
     * @param provenance the model that produced the answer
     */
    private static void proposition(
            DecisionResponseAssembler assembler, String name, NoulAnswer noul, ModelProvenance provenance) {
        var value = noul.value();
        if (!isProbability(value)) {
            assembler.unreadable(name, Anomaly.OUT_OF_RANGE);
        } else if (value == UNDECIDED) {
            assembler.proposition(name, new PropositionResult.Inconclusive(provenance));
        } else {
            assembler.proposition(name, new PropositionResult.Answered(value > UNDECIDED, provenance, value));
        }
    }

    /**
     * The probabilities must cover exactly the options and sum to 1. A confidence of 0 is
     * inconclusive, as in classify. The assembler checks that the selected label is an option.
     *
     * @param assembler collects the outcome
     * @param question the choice question
     * @param answer the native choice answer
     * @param provenance the model that produced the answer
     */
    private static void choice(
            DecisionResponseAssembler assembler,
            ChoiceQuestionSpec question,
            ChoiceAnswer answer,
            ModelProvenance provenance) {
        var name = question.getName();
        var optionIds = new HashSet<String>();
        question.getOptions().forEach(option -> optionIds.add(option.getId()));
        if (!answer.probabilities().keySet().equals(optionIds)
                || !isDistribution(answer.probabilities().values())) {
            assembler.unreadable(name, Anomaly.BAD_DISTRIBUTION);
        } else if (!isProbability(answer.confidence())) {
            assembler.unreadable(name, Anomaly.OUT_OF_RANGE);
        } else if (answer.confidence() == 0.0d) {
            assembler.choice(name, new ClassificationResult.Inconclusive(provenance));
        } else if (answer.value() == null || answer.value().isBlank()) {
            assembler.unreadable(name);
        } else {
            assembler.choice(
                    name, new ClassificationResult.Selected(answer.value(), provenance, answer.confidence()));
        }
    }

    /**
     * Score.java makes level 0 the first criteria entry, and ScoreAnswer keys the legend and the
     * probabilities by level index. The value is the expected level index. The SDK derives its nearest
     * level from the probabilities, so no level is reported as selected.
     *
     * @param assembler collects the outcome
     * @param question the rating question
     * @param answer the native score answer
     * @param provenance the model that produced the answer
     */
    private static void rating(
            DecisionResponseAssembler assembler,
            RatingQuestionSpec question,
            ScoreAnswer answer,
            ModelProvenance provenance) {
        var name = question.getName();
        var levels = question.getLevels();
        var indexes = new HashSet<Integer>();
        for (int i = 0; i < levels.size(); i++) {
            indexes.add(i);
        }
        if (!answer.legend().keySet().equals(indexes)
                || !answer.probabilities().keySet().equals(indexes)
                || !isDistribution(answer.probabilities().values())) {
            assembler.unreadable(name, Anomaly.BAD_DISTRIBUTION);
            return;
        }
        var value = answer.value();
        if (!Double.isFinite(value) || value < 0 || value > levels.size() - 1) {
            assembler.unreadable(name, Anomaly.OUT_OF_RANGE);
            return;
        }
        if (!isProbability(answer.confidence())) {
            assembler.unreadable(name, Anomaly.OUT_OF_RANGE);
            return;
        }
        List<LevelProbability> distribution = new ArrayList<>(levels.size());
        for (int i = 0; i < levels.size(); i++) {
            distribution.add(new LevelProbability(levels.get(i).getId(), answer.probabilities().get(i)));
        }
        assembler.rating(
                name,
                new RatingResult.Answered(
                        provenance,
                        null,
                        distribution,
                        new RatingScore(value, RatingStatistic.EXPECTED_LEVEL_INDEX),
                        answer.confidence()));
    }

    /**
     * Reports whether a value is a valid probability.
     *
     * @param value the value to check
     * @return true when it is finite and between 0 and 1
     */
    private static boolean isProbability(double value) {
        return Double.isFinite(value) && value >= 0.0d && value <= 1.0d;
    }

    /**
     * Reports whether a set of probabilities are all valid and sum to 1.
     *
     * @param probabilities the probabilities to check
     * @return true when every value is a valid probability and they sum to 1
     */
    private static boolean isDistribution(Collection<Double> probabilities) {
        var total = 0.0d;
        for (Double probability : probabilities) {
            if (probability == null || !isProbability(probability)) {
                return false;
            }
            total += probability;
        }
        return Math.abs(total - 1.0d) <= DISTRIBUTION_TOLERANCE;
    }
}
