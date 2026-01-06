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
package com.embabel.agent.api.annotation.support;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.EmbabelComponent;
import com.embabel.agent.api.dsl.SnakeMeal;
import com.embabel.agent.domain.io.UserInput;
import org.springframework.lang.Nullable;

@EmbabelComponent
class OneTransformerActionWithNullableParameterJavaSpring {

    @Action(cost = 500.0)
    PersonWithReverseTool toPerson(
            UserInput userInput,
            @Nullable SnakeMeal person
    ) {
        var content = userInput.getContent();
        if (person != null) {
            content += " and tasty!";
        }
        return new PersonWithReverseTool(content);
    }

}

@EmbabelComponent
class OneTransformerActionWithNullableParameterJavaJakarta {

    @Action(cost = 500.0)
    PersonWithReverseTool toPerson(
            UserInput userInput,
            @jakarta.annotation.Nullable SnakeMeal person
    ) {
        var content = userInput.getContent();
        if (person != null) {
            content += " and tasty!";
        }
        return new PersonWithReverseTool(content);
    }

}
