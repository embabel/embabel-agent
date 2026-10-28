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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;

import java.util.function.IntConsumer;

/**
 * Terminates the JVM once the shell has finished.
 *
 * <p>Spring Shell 4 handles {@code quit}/{@code exit} by ending its input loop; with
 * {@code spring.shell.context.close=true} it then closes the application context. That is not
 * enough when threads outside Spring's control (agent processes, tool executors, user code)
 * are still running, as they keep the JVM alive. {@link ApplicationReadyEvent} is published
 * after the shell runner returns, so this listener closes the context and exits the process
 * with the context's exit code.
 *
 * <p>Ordered first so it runs while the context is still active, before Spring Shell's own
 * listener closes it.
 */
public class ShellExitListener implements ApplicationListener<ApplicationReadyEvent>, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(ShellExitListener.class);

    private final IntConsumer exitProcess;

    public ShellExitListener() {
        this(System::exit);
    }

    ShellExitListener(IntConsumer exitProcess) {
        this.exitProcess = exitProcess;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        ConfigurableApplicationContext context = event.getApplicationContext();
        int exitCode = context.isActive() ? SpringApplication.exit(context) : 0;
        logger.debug("Shell finished - exiting with code {}", exitCode);
        exitProcess.accept(exitCode);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
