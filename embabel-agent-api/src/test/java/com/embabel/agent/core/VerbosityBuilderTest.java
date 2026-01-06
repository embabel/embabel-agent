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
package com.embabel.agent.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class VerbosityBuilderTest {

    @Test
    void constructorWithAllParameters() {
        var verbosity = new Verbosity(true, true, true, true);

        assertTrue(verbosity.getShowPrompts());
        assertTrue(verbosity.getShowLlmResponses());
        assertTrue(verbosity.getDebug());
        assertTrue(verbosity.getShowPlanning());
    }

    @Test
    void defaultConstructor() {
        var verbosity = new Verbosity();

        assertFalse(verbosity.getShowPrompts());
        assertFalse(verbosity.getShowLlmResponses());
        assertFalse(verbosity.getDebug());
        assertFalse(verbosity.getShowPlanning());
    }

    @Test
    void withers() {
        var verbosity = Verbosity.DEFAULT
                .withShowPrompts(true)
                .withShowLlmResponses(true)
                .withDebug(true)
                .withShowPlanning(true);

        assertTrue(verbosity.getShowPrompts());
        assertTrue(verbosity.getShowLlmResponses());
        assertTrue(verbosity.getDebug());
        assertTrue(verbosity.getShowPlanning());
    }

    @Test
    void simpleEnablers() {
        // Test showPrompts() simple enabler
        var v1 = Verbosity.DEFAULT.showPrompts();
        assertTrue(v1.getShowPrompts());
        assertFalse(v1.getShowLlmResponses());
        assertFalse(v1.getDebug());
        assertFalse(v1.getShowPlanning());

        // Test showLlmResponses() simple enabler
        var v2 = Verbosity.DEFAULT.showLlmResponses();
        assertFalse(v2.getShowPrompts());
        assertTrue(v2.getShowLlmResponses());
        assertFalse(v2.getDebug());
        assertFalse(v2.getShowPlanning());

        // Test debug() simple enabler
        var v3 = Verbosity.DEFAULT.debug();
        assertFalse(v3.getShowPrompts());
        assertFalse(v3.getShowLlmResponses());
        assertTrue(v3.getDebug());
        assertFalse(v3.getShowPlanning());

        // Test showPlanning() simple enabler
        var v4 = Verbosity.DEFAULT.showPlanning();
        assertFalse(v4.getShowPrompts());
        assertFalse(v4.getShowLlmResponses());
        assertFalse(v4.getDebug());
        assertTrue(v4.getShowPlanning());
    }

    @Test
    void chainedSimpleEnablers() {
        var verbosity = Verbosity.DEFAULT
                .showPrompts()
                .showLlmResponses()
                .debug()
                .showPlanning();

        assertTrue(verbosity.getShowPrompts());
        assertTrue(verbosity.getShowLlmResponses());
        assertTrue(verbosity.getDebug());
        assertTrue(verbosity.getShowPlanning());
    }

    @Test
    void defaultConstant() {
        assertEquals(new Verbosity(), Verbosity.DEFAULT);
    }

}
