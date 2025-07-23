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
package com.embabel.agent.api.annotation;

import java.lang.annotation.*;

/**
 * Annotation that can be added to an @Action method
 * to indicate that its execution achieves a goal
 * See {@link com.embabel.agent.core.Goal} for more details.
 * A Goal object will be created for each method annotated with this annotation.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AchievesGoal {

    /**
     * Description of the goal.
     * Take care in writing this description as it will be used
     * to choose a goal based on user input.
     */
    String description();

    /**
     * Value of achieving the goal
     */
    double value() default 0.0;

    /**
     * Set of tags describing classes or capabilities for this goal.
     * Example: ["cooking", "customer support", "billing"]
     */
    String[] tags() default {};

    /**
     * Set of example scenarios that the goal can achieve.
     * Example: ["I need a recipe for bread", "I want to support a customer with a billing issue"]
     */
    String[] examples() default {};

    /**
     * Any starting input types for the goal we might want to prompt for
     */
    // This annotation is implemented in Java because the following is impossible in Kotlin:
    Class<?>[] startingInputTypes() default {};
}