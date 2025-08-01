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

/**
 * How a goal should be exposed
 */
public @interface Export {

    /**
     * Set to override the name of the goal when it is exported.
     */
    String name() default "";

    boolean remote() default false;

    boolean local() default true;

    /**
     * Any starting input types for the goal we might want to prompt for
     */
    // This annotation is implemented in Java because the following is impossible in Kotlin:
    Class<?>[] startingInputTypes() default {};

}
