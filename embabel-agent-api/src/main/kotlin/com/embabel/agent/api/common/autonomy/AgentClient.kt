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
package com.embabel.agent.api.common.autonomy

import com.embabel.agent.api.common.SomeOf
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.domain.library.HasContent
import com.embabel.agent.domain.library.NewsStory
import com.embabel.agent.domain.library.Person
import java.util.concurrent.CompletableFuture

interface InvokableAgentClient<T, R> {

    fun execute(t: T): R

    fun executeAsync(t: T): CompletableFuture<R>
}

// Don't go beyond cardinality 2, put in a SomeOf composite
interface BiInvokableAgentClient<T, U, R> {

    fun execute(t: T, u: U): R

    fun executeAsync(t: T, u: U): CompletableFuture<R>
}

data class AgentClientBuilder(
    private val agentPlatform: AgentPlatform,
    private val processOptions: ProcessOptions = ProcessOptions()
) {

    inline fun <reified T, reified R> asFunction(): InvokableAgentClient<T, R> {
        return asFunction(T::class.java, R::class.java)
    }

    /**
     * Goal will be to type
     */
    fun <T, R> asFunction(
        from: Class<T>,
        to: Class<R>,
    ): InvokableAgentClient<T, R> {
        TODO()
    }

    /**
     * Allows more control over the invocation by specifying an intent.
     * Will work like Autonomy processing of UserInput.
     * Allows resolution between different goals returning the same type.
     */
    fun <T, R> asFunction(
        from: Class<T>,
        to: Class<R>,
        intent: String,
    ): InvokableAgentClient<T, R> {
        TODO()
    }

    // For cardinality 2
    fun <T, U, R> asBiFunction(
        first: Class<T>,
        second: Class<U>,
        to: Class<R>,
    ): BiInvokableAgentClient<T, U, R> {
        TODO()
    }

    // For > 2
    fun <T : SomeOf, R> asMultiFunction(
        composite: Class<T>,
        to: Class<R>,
    ): InvokableAgentClient<T, R> = // This should just work
        asFunction(composite, to)

    fun withProcessOptions(processOptions: ProcessOptions): AgentClientBuilder {
        return copy(processOptions = processOptions)
    }
}

/**
 * Enables objects to be pushed into the platform in a simple way
 */
class AgentClient {

    companion object {

        /**
         * Creates a new [AgentClient] instance.
         */
        @JvmStatic
        fun of(agentPlatform: AgentPlatform): AgentClientBuilder {
            TODO()
        }
    }
}

class Usage {
    init {

        // Kotlin
        val person1 = AgentClient.of(TODO())
            .withProcessOptions(ProcessOptions())
            .asFunction<UserInput, Person>() // client is reusable
            .execute(UserInput("John Doe"))


        // Java
        val person2 = AgentClient.of(TODO())
            .withProcessOptions(ProcessOptions()) // TODO Java-friendly approach to ProcessOptions here, probably on the AgentClientBuilder versus on ProcessOptions
            .asFunction(UserInput::class.java, Person::class.java)
            .execute(UserInput("John Doe"))

        // Cardinality 2 example
        val person3 = AgentClient.of(TODO())
            .withProcessOptions(ProcessOptions()) // TODO apply Java-friendly approach here, probably on the AgentClientBuilder versus on ProcessOptions
            .asBiFunction(UserInput::class.java, NewsStory::class.java, Person::class.java)
            .execute(UserInput("John Doe"), NewsStory("url", "Breaking News: Embabel is awesome!", "summary"))

        // More than 2 parameters. SomeOf is treated specially by platform
        data class BindAllThese(
            val userInput: UserInput,
            val newsStory: NewsStory,
            val hasContent: HasContent,
        ) : SomeOf

        val person4 = AgentClient.of(TODO())
            .withProcessOptions(ProcessOptions()) // TODO apply Java-friendly approach here, probably on the AgentClientBuilder versus on ProcessOptions
            .asMultiFunction(BindAllThese::class.java, Person::class.java)
            .execute(
                BindAllThese(
                    UserInput("John Doe"),
                    NewsStory("url", "Breaking News: Embabel is awesome!", "summary"),
                    TODO()
                )
            )

        // Notes:

        // Would be nice to be able to add custom listeners, could be added to ProcessOptions

        // Would love to be able to inject an InvokableAgentClient but erasure is a problem here.

        // Probably also want one InvokableAgentClient that you can bind anything to, and which
        // doesn't throw an exception if no goal is found. Like "See if you can do anyying with this"

    }
}

