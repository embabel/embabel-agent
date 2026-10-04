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
package com.embabel.agent.api.tool;

import com.embabel.agent.api.annotation.LlmTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class JavaGenericToolArgumentsTest {

    public record Item(String name) {}

    public static class InventoryTools {
        @LlmTool(description = "Read the first item name")
        public String firstName(List<Item> items) {
            return items.getFirst().name();
        }

        @LlmTool(description = "Read an indexed item name")
        public String indexedName(Map<String, Item> items) {
            return items.get("first").name();
        }

        @LlmTool(description = "Read the first identifier")
        public String firstId(List<Long> ids) {
            return Long.toString(ids.getFirst());
        }

        @LlmTool(description = "Read a single item name")
        public String singleName(Item item) {
            return item.name();
        }
    }

    @Test
    void convertsListElementsToTheDeclaredRecordType() {
        assertText("firstName", "{\"items\":[{\"name\":\"book\"}]}", "book");
    }

    @Test
    void convertsMapValuesToTheDeclaredRecordType() {
        assertText("indexedName", "{\"items\":{\"first\":{\"name\":\"book\"}}}", "book");
    }

    @Test
    void convertsIntegerJsonValuesToDeclaredLongElements() {
        assertText("firstId", "{\"ids\":[1]}", "1");
    }

    @Test
    void convertsANonGenericRecordParameter() {
        assertText("singleName", "{\"item\":{\"name\":\"book\"}}", "book");
    }

    private void assertText(String name, String input, String expected) {
        var tool = Tool.fromInstance(new InventoryTools()).stream()
                .filter(candidate -> candidate.getDefinition().getName().equals(name))
                .findFirst()
                .orElseThrow();
        var result = tool.call(input);
        var text = assertInstanceOf(Tool.Result.Text.class, result, result.toString());
        assertEquals(expected, text.getContent());
    }
}
