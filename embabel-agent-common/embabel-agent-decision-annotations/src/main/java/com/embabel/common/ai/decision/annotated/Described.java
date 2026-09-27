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
package com.embabel.common.ai.decision.annotated;

import org.jetbrains.annotations.ApiStatus;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gives the description the model sees for an enum constant used as a choice option or a rating
 * level.
 * <p>
 * Every constant of a {@link ChoiceQuestion} enum needs this annotation. A constant of a
 * {@link RatingQuestion} enum may omit it, and its description is then the level id.
 */
@ApiStatus.Experimental
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Described {

    /**
     * The description of the option or level.
     *
     * @return the description, which should say when the model picks this constant
     */
    String value();
}
