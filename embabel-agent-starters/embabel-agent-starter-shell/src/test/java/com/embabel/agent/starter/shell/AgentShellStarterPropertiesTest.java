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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentShellStarterPropertiesTest {

    @Test
    void testDefaultValues() {
        AgentShellStarterProperties properties = new AgentShellStarterProperties();

        assertEquals("none", properties.getWebApplicationType());
        assertNotNull(properties.getCommand());
        assertNotNull(properties.getInteractive());

        // Command defaults - updated to match new defaults
        assertFalse(properties.getCommand().isExitEnabled());
        assertFalse(properties.getCommand().isQuitEnabled());

        // Interactive defaults
        assertTrue(properties.getInteractive().isEnabled());
        assertTrue(properties.getInteractive().isHistoryEnabled());
    }

    @Test
    void testSettersAndGetters() {
        AgentShellStarterProperties properties = new AgentShellStarterProperties();

        // Main properties
        properties.setWebApplicationType("servlet");
        assertEquals("servlet", properties.getWebApplicationType());

        properties.setWebApplicationType("reactive");
        assertEquals("reactive", properties.getWebApplicationType());

        // Command properties - can't set new Command object since it's final
        properties.getCommand().setExitEnabled(false);
        properties.getCommand().setQuitEnabled(false);

        assertFalse(properties.getCommand().isExitEnabled());
        assertFalse(properties.getCommand().isQuitEnabled());

        // Interactive properties - can't set new Interactive object since it's final
        properties.getInteractive().setEnabled(false);
        properties.getInteractive().setHistoryEnabled(false);

        assertFalse(properties.getInteractive().isEnabled());
        assertFalse(properties.getInteractive().isHistoryEnabled());
    }

    @Test
    void testCommandSettersAndGetters() {
        AgentShellStarterProperties.Command cmd = new AgentShellStarterProperties.Command();

        // Test setting to false (since default is now true)
        cmd.setExitEnabled(false);
        cmd.setQuitEnabled(false);

        assertFalse(cmd.isExitEnabled());
        assertFalse(cmd.isQuitEnabled());

        // Test setting back to true
        cmd.setExitEnabled(true);
        cmd.setQuitEnabled(true);

        assertTrue(cmd.isExitEnabled());
        assertTrue(cmd.isQuitEnabled());
    }

    @Test
    void testInteractiveSettersAndGetters() {
        AgentShellStarterProperties.Interactive interactive = new AgentShellStarterProperties.Interactive();

        interactive.setEnabled(false);
        interactive.setHistoryEnabled(false);

        assertFalse(interactive.isEnabled());
        assertFalse(interactive.isHistoryEnabled());

        interactive.setEnabled(true);
        interactive.setHistoryEnabled(true);

        assertTrue(interactive.isEnabled());
        assertTrue(interactive.isHistoryEnabled());
    }

    @Test
    void testWebApplicationTypeValidation() {
        AgentShellStarterProperties properties = new AgentShellStarterProperties();

        // Test valid values
        properties.setWebApplicationType("none");
        assertEquals("none", properties.getWebApplicationType());

        properties.setWebApplicationType("servlet");
        assertEquals("servlet", properties.getWebApplicationType());

        properties.setWebApplicationType("reactive");
        assertEquals("reactive", properties.getWebApplicationType());
    }

    @Test
    void testNullValues() {
        AgentShellStarterProperties properties = new AgentShellStarterProperties();

        // Test setting null webApplicationType
        properties.setWebApplicationType(null);
        assertNull(properties.getWebApplicationType());

        // Command and Interactive objects are final and cannot be set to null
        // They should always return non-null instances
        assertNotNull(properties.getCommand());
        assertNotNull(properties.getInteractive());
    }

    @Test
    void testNestedObjectsAreFinal() {
        AgentShellStarterProperties properties = new AgentShellStarterProperties();

        // Get references to nested objects
        AgentShellStarterProperties.Command command1 = properties.getCommand();
        AgentShellStarterProperties.Interactive interactive1 = properties.getInteractive();

        // Get references again
        AgentShellStarterProperties.Command command2 = properties.getCommand();
        AgentShellStarterProperties.Interactive interactive2 = properties.getInteractive();

        // Should be the same instances since they're final
        assertSame(command1, command2);
        assertSame(interactive1, interactive2);
    }
}