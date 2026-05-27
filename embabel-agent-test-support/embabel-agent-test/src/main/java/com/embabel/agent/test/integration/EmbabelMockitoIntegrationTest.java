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
package com.embabel.agent.test.integration;

import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.internal.LlmOperations;
import com.embabel.agent.core.internal.streaming.StreamingLlmOperations;
import com.embabel.agent.core.internal.streaming.StreamingLlmOperationsFactory;
import com.embabel.agent.core.support.LlmInteraction;
import com.embabel.chat.Message;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.common.ai.model.ModelProvider;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;
import org.mockito.stubbing.OngoingStubbing;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Predicate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Base class for integration tests that use Mockito to mock LLM operations.
 * Provides convenient methods for stubbing and verifying LLM interactions.
 * Subclasses will be Spring Boot tests that start the AgentPlatform.
 * Prompt matching is done with normal lambdas rather than Mockito.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "embabel.models.default-llm=test-model",
        "embabel.agent.verbosity.debug=true",
        "spring.shell.interactive.enabled=false",
        "spring.shell.noninteractive.enabled=false"
})
public class EmbabelMockitoIntegrationTest {

    @Autowired
    protected AgentPlatform agentPlatform;

    @MockitoBean
    private ModelProvider modelProvider;

    @MockitoBean(extraInterfaces = {StreamingLlmOperations.class, StreamingLlmOperationsFactory.class})
    protected LlmOperations llmOperations;

    protected StreamingLlmOperations streamingLlmOperations;

    protected StreamingLlmOperationsFactory streamingLlmOperationsFactory;

    @BeforeEach
    void initMocks() {
        streamingLlmOperations = (StreamingLlmOperations) llmOperations;
        streamingLlmOperationsFactory = (StreamingLlmOperationsFactory) llmOperations;
    }

    protected void supportsStreaming(boolean supportsStreaming) {
      when(streamingLlmOperationsFactory.supportsStreaming(any(LlmOptions.class))).thenReturn(supportsStreaming);
      if (supportsStreaming) {
          when(streamingLlmOperationsFactory.createStreamingOperations(any(LlmOptions.class))).thenReturn(streamingLlmOperations);
      }
    }

    // Stubbing methods
    protected <T> OngoingStubbing<T> whenCreateObject(Predicate<String> promptMatcher, Class<T> outputClass, Predicate<LlmInteraction> llmInteractionPredicate) {
        // Mock the lower level LLM operation to create an object
        // that will ultimately be called
      return when(
                llmOperations.createObject(argThat(m -> firstMessageContentSatisfiesMatcher(m, promptMatcher)), argThat(llmInteractionPredicate::test), eq(outputClass), any(), any()));
    }

    protected <T> OngoingStubbing<T> whenCreateObject(Predicate<String> promptMatcher, Class<T> outputClass) {
        return whenCreateObject(promptMatcher, outputClass, llmi -> true);
    }

    protected <T> OngoingStubbing<Flux<T>> whenCreateObjectStream(Predicate<String> promptMatcher, Class<T> outputClass, Predicate<LlmInteraction> llmInteractionPredicate) {
        return when(
                streamingLlmOperations.createObjectStream(argThat(m -> firstMessageContentSatisfiesMatcher(m, promptMatcher)), argThat(llmInteractionPredicate::test), eq(outputClass), any(), any()));
    }

    protected <T> OngoingStubbing<Flux<T>> whenCreateObjectStream(Predicate<String> promptMatcher, Class<T> outputClass) {
        return whenCreateObjectStream(promptMatcher, outputClass, llmi -> true);
    }

    protected OngoingStubbing<String> whenGenerateText(Predicate<String> promptMatcher, Predicate<LlmInteraction> llmInteractionMatcher) {
        return when(llmOperations.createObject(argThat(m -> firstMessageContentSatisfiesMatcher(m, promptMatcher)),
                argThat(llmInteractionMatcher::test), eq(String.class), any(), any()));
    }

    protected OngoingStubbing<String> whenGenerateText(Predicate<String> promptMatcher) {
        return whenGenerateText(promptMatcher, llmi -> true);
    }

    protected OngoingStubbing<Flux<String>> whenGenerateStream(Predicate<String> promptMatcher, Predicate<LlmInteraction> llmInteractionMatcher) {
       return when(streamingLlmOperations.generateStream(argThat((List<? extends Message> m) -> firstMessageContentSatisfiesMatcher(m, promptMatcher)),
                argThat(llmInteractionMatcher::test), any(), any()));
    }

    protected OngoingStubbing<Flux<String>> whenGenerateStream(Predicate<String> promptMatcher) {
        return whenGenerateStream(promptMatcher, llmi -> true);
    }

    // Verification methods
    protected <T> void verifyCreateObject(Predicate<String> promptMatcher, Class<T> outputClass, Predicate<LlmInteraction> llmInteractionMatcher) {
        verify(llmOperations).createObject(argThat(m -> firstMessageContentSatisfiesMatcher(m, promptMatcher)),
                argThat(llmInteractionMatcher::test), eq(outputClass), any(), any());
    }

    protected <T> void verifyCreateObject(Predicate<String> prompt, Class<T> outputClass) {
        verifyCreateObject(prompt, outputClass, llmi -> true);
    }

    protected <T> void verifyCreateObjectStream(Predicate<String> promptMatcher, Class<T> outputClass, Predicate<LlmInteraction> llmInteractionMatcher) {
        verify(streamingLlmOperations).createObjectStream(argThat(m -> firstMessageContentSatisfiesMatcher(m, promptMatcher)),
                argThat(llmInteractionMatcher::test), eq(outputClass), any(), any());
    }

    protected <T> void verifyCreateObjectStream(Predicate<String> prompt, Class<T> outputClass) {
        verifyCreateObjectStream(prompt, outputClass, llmi -> true);
    }

    protected void verifyGenerateText(Predicate<String> promptMatcher, Predicate<LlmInteraction> llmInteractionMatcher) {
        verify(llmOperations).createObject(argThat(m -> firstMessageContentSatisfiesMatcher(m, promptMatcher)), argThat(llmInteractionMatcher::test), eq(String.class), any(), any());
    }

    protected void verifyGenerateText(Predicate<String> promptMatcher) {
        verifyGenerateText(promptMatcher, llmi -> true);
    }

    protected void verifyGenerateStream(Predicate<String> promptMatcher, Predicate<LlmInteraction> llmInteractionMatcher) {
        verify(streamingLlmOperations).generateStream(argThat((List<? extends Message> m) -> firstMessageContentSatisfiesMatcher(m, promptMatcher)), argThat(llmInteractionMatcher::test), any(), any());
    }

    protected void verifyGenerateStream(Predicate<String> promptMatcher) {
        verifyGenerateStream(promptMatcher, llmi -> true);
    }

    // Verification methods with argument matchers
    protected <T> void verifyCreateObjectMatching(Predicate<String> promptMatcher, Class<T> outputClass, ArgumentMatcher<LlmInteraction> llmInteractionMatcher) {
        verify(llmOperations).createObject(argThat(m -> firstMessageContentSatisfiesMatcher(m, promptMatcher)), argThat(llmInteractionMatcher), eq(outputClass), any(), any());
    }

    protected <T> void verifyCreateObjectMatchingMessages(ArgumentMatcher<List<Message>> promptMatcher, Class<T> outputClass, ArgumentMatcher<LlmInteraction> llmInteractionMatcher) {
        verify(llmOperations).createObject(argThat(promptMatcher),
                argThat(llmInteractionMatcher),
                eq(outputClass), any(), any());
    }

    protected <T> void verifyCreateObjectStreamMatching(Predicate<String> promptMatcher, Class<T> outputClass, ArgumentMatcher<LlmInteraction> llmInteractionMatcher) {
        verify(streamingLlmOperations).createObjectStream(argThat(m -> firstMessageContentSatisfiesMatcher(m, promptMatcher)), argThat(llmInteractionMatcher), eq(outputClass), any(), any());
    }

    protected <T> void verifyCreateObjectStreamMatchingMessages(ArgumentMatcher<List<Message>> promptMatcher, Class<T> outputClass, ArgumentMatcher<LlmInteraction> llmInteractionMatcher) {
        verify(streamingLlmOperations).createObjectStream(argThat(promptMatcher),
                argThat(llmInteractionMatcher),
                eq(outputClass), any(), any());
    }

    protected void verifyGenerateTextMatching(Predicate<String> promptMatcher) {
        verify(llmOperations).createObject(argThat(messages -> firstMessageContentSatisfiesMatcher(messages, promptMatcher)), any(), eq(String.class), any(), any());
    }

    protected void verifyGenerateTextMatching(Predicate<String> promptMatcher, LlmInteraction llmInteraction) {
        Mockito.verify(llmOperations)
                .createObject(argThat(messages -> firstMessageContentSatisfiesMatcher(messages, promptMatcher)), eq(llmInteraction), eq(String.class), any(), any());
    }

    protected void verifyGenerateStreamMatching(Predicate<String> promptMatcher) {
        verify(streamingLlmOperations).generateStream(argThat((List<? extends Message> messages) -> firstMessageContentSatisfiesMatcher(messages, promptMatcher)), any(), any(), any());
    }

    protected void verifyGenerateStreamMatching(Predicate<String> promptMatcher, LlmInteraction llmInteraction) {
        Mockito.verify(streamingLlmOperations)
                .generateStream(argThat((List<? extends Message> messages) -> firstMessageContentSatisfiesMatcher(messages, promptMatcher)), eq(llmInteraction), any(), any());
    }

    // Convenience verification methods
    protected void verifyNoInteractions() {
        Mockito.verifyNoInteractions(llmOperations);
    }

    protected void verifyNoMoreInteractions() {
        Mockito.verify(streamingLlmOperationsFactory, Mockito.atLeast(0)).supportsStreaming(any(LlmOptions.class)); // These calls should not be verified
        Mockito.verify(streamingLlmOperationsFactory, Mockito.atLeast(0)).createStreamingOperations(any(LlmOptions.class));
        Mockito.verifyNoMoreInteractions(llmOperations);
    }

    // Argument captor helpers
    protected ArgumentCaptor<String> capturePrompt() {
        return ArgumentCaptor.forClass(String.class);
    }

    protected ArgumentCaptor<LlmInteraction> captureLlmInteraction() {
        return ArgumentCaptor.forClass(LlmInteraction.class);
    }

    protected <T> ArgumentCaptor<Class<T>> captureOutputClass() {
        return ArgumentCaptor.forClass(Class.class);
    }


    private boolean firstMessageContentSatisfiesMatcher(List<? extends Message> messages, Predicate<String> contentMatcher) {
        return messages != null && messages.size() == 1 && contentMatcher.test(messages.getFirst().getContent());
    }

}