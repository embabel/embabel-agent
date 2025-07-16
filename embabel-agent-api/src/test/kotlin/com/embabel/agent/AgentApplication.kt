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
package com.embabel.agent
import com.embabel.common.util.WinUtils
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan

@SpringBootApplication
@ConfigurationPropertiesScan(
    basePackages = [
        "com.embabel.agent",
        "com.embabel.example",
    ]
)
@ComponentScan(
    basePackages = [
        "com.embabel.agent",
        "com.embabel.example",
    ]
)
class AgentApplication {
    companion object {
        init {
            if (WinUtils.IS_OS_WINDOWS()) {
                // Set console to UTF-8 on Windows and optimize font for Unicode display
                // This is necessary to display non-ASCII characters correctly
                WinUtils.CHCP_TO_UTF8()
                WinUtils.SETUP_OPTIMAL_CONSOLE()
            }
        }
    }
}

fun main(args: Array<String>) {
    runApplication<AgentApplication>(*args)
}
