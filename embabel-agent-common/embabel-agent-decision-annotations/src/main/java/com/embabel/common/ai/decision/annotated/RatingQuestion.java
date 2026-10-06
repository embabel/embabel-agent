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
 * Declares that a property is a rating question in the decision spec read from its type.
 * <p>
 * The property type is an enum with at least two constants, or RatingResult with {@link #levels()} naming that enum. Each constant is a level, lowest
 * first in declaration order. The level id comes from {@link DecisionId}, or the constant's serialized form under the mapper.
 * A constant's description comes from {@link Described}, or is the level id when the constant
 * has none.
 * <p>
 * The question name comes from {@link #name()}, or the Jackson property name. Put the annotation on a record component, or
 * on the field, getter, setter or creator parameter of a Jackson property. Jackson merges these
 * members into one property, so the annotation may appear on several of them with the same
 * {@code asking} text.
 */
@ApiStatus.Experimental
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
public @interface RatingQuestion {

    /**
     * The instructions the model receives for this question.
     *
     * @return the question's instructions, which must not be blank
     */
    String asking();

    /**
     * Stable question name. An empty value uses the Jackson property name.
     *
     * @return the provider-facing name, independent of the property's JSON name
     */
    String name() default "";


    /**
     * Levels for a property declared as RatingResult. Enum-valued properties infer their levels
     * from their own type and must leave this unset.
     *
     * @return the concrete enum defining the scale, lowest first, or Void when inferred
     */
    Class<?> levels() default Void.class;

}
