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
package com.embabel.agent.starter.shell;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

/**
 * Configuration properties for Embabel Agent Shell Starter.
 *
 * <p>Provides configuration options for shell behavior when the agent operates
 * in interactive command-line mode. These properties control Spring Shell behavior
 * and application startup characteristics.
 *
 * <p>Properties are bound from configuration with the prefix {@code embabel.agent.shell}:
 * <pre>
 * embabel.agent.shell.web-application-type=none
 * embabel.agent.shell.command.exit-enabled=true
 * embabel.agent.shell.interactive.enabled=true
 * </pre>
 *
 * @since 1.1
 */
@ConfigurationProperties(prefix = "embabel.agent.shell")
@Validated
public class AgentShellStarterProperties {

    /**
     * Spring Boot web application type for shell mode.
     * Must be one of: "none", "servlet", or "reactive".
     * Defaults to "none" to prevent web server startup in shell mode.
     */
    @NotNull
    @Pattern(regexp = "none|servlet|reactive",
            message = "Web application type must be 'none', 'servlet', or 'reactive'")
    private String webApplicationType = "none";

    /**
     * Shell command configuration.
     */
    @Valid
    @NotNull
    private final Command command = new Command();

    /**
     * Interactive shell configuration.
     */
    @Valid
    @NotNull
    private final Interactive interactive = new Interactive();

    public String getWebApplicationType() {
        return webApplicationType;
    }

    public void setWebApplicationType(String webApplicationType) {
        this.webApplicationType = webApplicationType;
    }

    public Command getCommand() {
        return command;
    }

    public Interactive getInteractive() {
        return interactive;
    }

    /**
     * Configuration for shell command behavior.
     */
    public static class Command {

        /**
         * Enable the 'exit' command to allow users to terminate the shell.
         * Defaults to true for standard shell behavior.
         */
        private boolean exitEnabled = false;

        /**
         * Enable the 'quit' command as an alias for exit.
         * Defaults to true for standard shell behavior.
         */
        private boolean quitEnabled = false;

        public boolean isExitEnabled() {
            return exitEnabled;
        }

        public void setExitEnabled(boolean exitEnabled) {
            this.exitEnabled = exitEnabled;
        }

        public boolean isQuitEnabled() {
            return quitEnabled;
        }

        public void setQuitEnabled(boolean quitEnabled) {
            this.quitEnabled = quitEnabled;
        }
    }

    /**
     * Configuration for interactive shell features.
     */
    public static class Interactive {

        /**
         * Enable interactive shell mode with command prompt.
         * When false, the shell operates in non-interactive batch mode.
         */
        private boolean enabled = true;

        /**
         * Enable command history functionality.
         * Allows users to navigate previous commands using arrow keys.
         */
        private boolean historyEnabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isHistoryEnabled() {
            return historyEnabled;
        }

        public void setHistoryEnabled(boolean historyEnabled) {
            this.historyEnabled = historyEnabled;
        }
    }
}