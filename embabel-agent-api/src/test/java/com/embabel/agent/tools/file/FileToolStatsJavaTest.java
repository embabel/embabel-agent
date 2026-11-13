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
package com.embabel.agent.tools.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileToolStatsJavaTest {

    @TempDir
    Path tempDir;

    @Test
    void exposesFileToolStatsToJava() {
        FileTools fileTools = FileTools.readWrite(tempDir.toString());
        String content = "Hello, 世界";
        fileTools.createFile("greeting.txt", content);
        fileTools.readFile("greeting.txt");

        FileToolStats stats = fileTools.getFileToolStats();

        assertEquals(1, stats.getNumberOfChanges());
        assertEquals(content.getBytes(StandardCharsets.UTF_8).length, stats.getBytesReceived());
    }
}
