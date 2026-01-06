/*
 * Copyright 2024-2025 Embabel Pty Ltd.
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
package com.embabel.agent.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BudgetBuilderTest {

    @Test
    void constructorWithAllParameters() {
        var budget = new Budget(1.0, 2, 3);

        assertEquals(1.0, budget.getCost());
        assertEquals(2, budget.getActions());
        assertEquals(3, budget.getTokens());
    }

    @Test
    void defaultConstructor() {
        var budget = new Budget();

        assertEquals(Budget.DEFAULT_COST_LIMIT, budget.getCost());
        assertEquals(Budget.DEFAULT_ACTION_LIMIT, budget.getActions());
        assertEquals(Budget.DEFAULT_TOKEN_LIMIT, budget.getTokens());
    }

    @Test
    void withers() {
        var budget = Budget.DEFAULT
                .withCost(1.0)
                .withActions(2)
                .withTokens(3);

        assertEquals(1.0, budget.getCost());
        assertEquals(2, budget.getActions());
        assertEquals(3, budget.getTokens());
    }

    @Test
    void defaultConstant() {
        assertEquals(new Budget(), Budget.DEFAULT);
    }

}
