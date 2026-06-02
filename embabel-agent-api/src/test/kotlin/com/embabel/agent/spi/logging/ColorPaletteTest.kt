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
package com.embabel.agent.spi.logging

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ColorPaletteTest {

    @Test
    fun `should create DefaultColorPalette with default colors`() {
        // Arrange & Act
        val palette = DefaultColorPalette()

        // Assert
        assertEquals(0xbeb780, palette.highlight)
        assertEquals(0x7da17e, palette.color2)
    }

    @Test
    fun `should create DefaultColorPalette with custom highlight color`() {
        // Arrange & Act
        val palette = DefaultColorPalette(highlight = 0xFF0000)

        // Assert
        assertEquals(0xFF0000, palette.highlight)
        assertEquals(0x7da17e, palette.color2)
    }

    @Test
    fun `should create DefaultColorPalette with custom color2`() {
        // Arrange & Act
        val palette = DefaultColorPalette(color2 = 0x00FF00)

        // Assert
        assertEquals(0xbeb780, palette.highlight)
        assertEquals(0x00FF00, palette.color2)
    }

    @Test
    fun `should create DefaultColorPalette with both custom colors`() {
        // Arrange & Act
        val palette = DefaultColorPalette(
            highlight = 0xFF0000,
            color2 = 0x00FF00
        )

        // Assert
        assertEquals(0xFF0000, palette.highlight)
        assertEquals(0x00FF00, palette.color2)
    }

    @Test
    fun `should implement ColorPalette interface`() {
        // Arrange & Act
        val palette = DefaultColorPalette()

        // Assert
        assertTrue(palette is ColorPalette)
    }

    @Test
    fun `should support copy with modified highlight`() {
        // Arrange
        val original = DefaultColorPalette()

        // Act
        val modified = original.copy(highlight = 0xABCDEF)

        // Assert
        assertEquals(0xABCDEF, modified.highlight)
        assertEquals(0xbeb780, original.highlight)
    }

    @Test
    fun `should support copy with modified color2`() {
        // Arrange
        val original = DefaultColorPalette()

        // Act
        val modified = original.copy(color2 = 0x123456)

        // Assert
        assertEquals(0x123456, modified.color2)
        assertEquals(0x7da17e, original.color2)
    }

    @Test
    fun `should support equality`() {
        // Arrange
        val palette1 = DefaultColorPalette(highlight = 0xFF0000, color2 = 0x00FF00)
        val palette2 = DefaultColorPalette(highlight = 0xFF0000, color2 = 0x00FF00)
        val palette3 = DefaultColorPalette(highlight = 0x000000, color2 = 0x00FF00)

        // Assert
        assertEquals(palette1, palette2)
        assertNotEquals(palette1, palette3)
    }

    @Test
    fun `should allow zero values for colors`() {
        // Arrange & Act
        val palette = DefaultColorPalette(highlight = 0, color2 = 0)

        // Assert
        assertEquals(0, palette.highlight)
        assertEquals(0, palette.color2)
    }

    @Test
    fun `should allow maximum hex values for colors`() {
        // Arrange & Act
        val palette = DefaultColorPalette(
            highlight = 0xFFFFFF,
            color2 = 0xFFFFFF
        )

        // Assert
        assertEquals(0xFFFFFF, palette.highlight)
        assertEquals(0xFFFFFF, palette.color2)
    }
}
