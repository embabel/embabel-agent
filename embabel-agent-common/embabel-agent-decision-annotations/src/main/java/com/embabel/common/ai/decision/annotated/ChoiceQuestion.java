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
 * Declares that a property is a choice question in the decision spec read from its type.
 * <p>
 * The property type is an enum. Each constant is an option, in declaration order. The option id
 * is the constant's serialized form under the mapper, and every constant needs {@link Described}
 * to give the option's description.
 * <p>
 * The question name is the Jackson property name. Put the annotation on a record component, or
 * on the field, getter, setter or creator parameter of a Jackson property. Jackson merges these
 * members into one property, so the annotation may appear on several of them with the same
 * {@code asking} text.
 */
@ApiStatus.Experimental
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
public @interface ChoiceQuestion {

    /**
     * The instructions the model receives for this question.
     *
     * @return the question's instructions, which must not be blank
     */
    String asking();
}
