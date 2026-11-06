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

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/**
 * Auto-configuration class for Embabel Agent Shell.
 *
 * <p>This class enables component scanning for the {@code com.embabel.agent.shell} package,
 * allowing Spring to detect and register shell-related components, commands, and services
 * when the application is started in shell mode.
 *
 * <p>When Spring Shell is configured to close the context after the shell ends
 * ({@code spring.shell.context.close=true}, defaulted by
 * {@link com.embabel.agent.autoconfigure.shell.spi.ShellEnvironmentPostProcessor}), it also
 * registers a {@link ShellExitListener} that forces the JVM to exit.
 *
 * @since 0.3.1
 */
@AutoConfiguration
@ComponentScan(basePackages = "com.embabel.agent.shell")
public class AgentShellAutoConfiguration {

    @Bean
    @ConditionalOnProperty(name = "spring.shell.context.close", havingValue = "true")
    public ShellExitListener shellExitListener() {
        return new ShellExitListener();
    }
}
