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
package com.embabel.agent.api.invocation;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.EmbabelComponent;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.domain.io.UserInput;

/**
 * Test fixture: Actions for meal preparation workflow.
 * Used to test supervisor orchestration with multiple dependent stages.
 */
@EmbabelComponent
public class MealPreparationStages {

    /**
     * Represents a cook who will prepare the meal.
     */
    public record Cook(String name, int age) {
    }

    /**
     * Represents a customer's food order.
     */
    public record Order(String dish, int quantity) {
    }

    /**
     * The final meal produced by the cook.
     */
    public record Meal(String dish, int quantity, String orderedBy, String cookedBy) {
    }

    @Action(description = "Choose a cook from the user input")
    public Cook chooseCook(UserInput userInput, Ai ai) {
        // In a real scenario, this would use AI to extract cook info
        // For testing, we return a deterministic result based on input
        return new Cook("Chef " + userInput.getContent().substring(0, Math.min(5, userInput.getContent().length())), 35);
    }

    @Action(description = "Take a food order from user input")
    public Order takeOrder(UserInput userInput, Ai ai) {
        // In a real scenario, this would use AI to extract order info
        // For testing, we return a deterministic result based on input
        return new Order("Dish from " + userInput.getContent().substring(0, Math.min(10, userInput.getContent().length())), 1);
    }

    @Action(description = "Prepare the final meal")
    @AchievesGoal(description = "Cook the meal according to the order")
    public Meal prepareMeal(Cook cook, Order order, Ai ai) {
        // Combine cook and order to produce final meal
        return new Meal(
            order.dish(),
            order.quantity(),
            "Customer",
            cook.name()
        );
    }
}
