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
package com.embabel.agent.skills.script

import java.nio.file.Files
import java.nio.file.Path

/**
 * Copy [source] into [dir] under its file name, disambiguating with a numeric suffix
 * (e.g. `report.csv` -> `report-1.csv`) when that name is already taken. This lets
 * several input files that share a base name (from different folders) coexist in a flat
 * input directory instead of colliding on the same target.
 *
 * @return the path the file was copied to
 */
internal fun copyIntoUniqueName(source: Path, dir: Path): Path {
    val name = source.fileName.toString()
    var target = dir.resolve(name)
    if (Files.exists(target)) {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        do {
            target = dir.resolve("$base-$i$ext")
            i++
        } while (Files.exists(target))
    }
    Files.copy(source, target)
    return target
}
