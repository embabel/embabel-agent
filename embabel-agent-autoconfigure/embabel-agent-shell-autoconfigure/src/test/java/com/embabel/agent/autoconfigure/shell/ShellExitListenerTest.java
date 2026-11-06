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
package com.embabel.agent.autoconfigure.shell;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.Ordered;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies that the JVM is terminated once the shell has finished, with the exit code from the context.
 */
class ShellExitListenerTest {

    private final List<Integer> exitCodes = new ArrayList<>();

    private final ShellExitListener listener = new ShellExitListener(exitCodes::add);

    @Test
    void closesContextAndExitsProcessWithContextExitCode() {
        // Arrange
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean(ExitCodeGenerator.class, () -> () -> 3);
        context.refresh();

        // Act
        listener.onApplicationEvent(readyEvent(context));

        // Assert
        assertThat(context.isActive()).isFalse();
        assertThat(exitCodes).containsExactly(3);
    }

    @Test
    void exitsProcessWithZeroWhenContextAlreadyClosed() {
        // Arrange
        GenericApplicationContext context = new GenericApplicationContext();
        context.refresh();
        context.close();

        // Act
        listener.onApplicationEvent(readyEvent(context));

        // Assert
        assertThat(exitCodes).containsExactly(0);
    }

    @Test
    void runsBeforeSpringShellContextCloseListener() {
        // Spring Shell's own (unordered) listener closes the context on the same event
        assertThat(listener.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    private static ApplicationReadyEvent readyEvent(ConfigurableApplicationContext context) {
        return new ApplicationReadyEvent(mock(SpringApplication.class), new String[0], context, Duration.ZERO);
    }
}
